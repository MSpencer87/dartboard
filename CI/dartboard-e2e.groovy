#!groovy
// Declarative Pipeline Syntax
// Fixed flow: deploy -> load -> Qase-driven k6 test suite -> Slack summary -> optional destroy.
// For ad-hoc single commands (or a later manual destroy of a deployment left up via
// CLEANUP=false), use dartboard-choice.groovy with DEPLOYMENT_ID instead.
@Library('qa-jenkins-library') _

def agentLabel = 'jenkins-qa-jenkins-agent'
if (params.HARVESTER_KUBECONFIG) {
    agentLabel = 'vsphere-vpn-1'
}

def scmWorkspace
def generatedNames
def configDirPath
def parseToHTML(text) { text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }

def runningContainerName
def finalSSHKeyName
def finalSSHPemKey
def finalProjectName
def K6ThresholdsHaveFailed = 99 // Custom exit code to indicate k6 thresholds were crossed but iterations completed

def baseURL
def kubeconfigContainerPath
def numTestCases = 0
def sanitizeCharacterRegex = "[^a-zA-Z0-9'_-]"
def sanitizeK6EnvRegex = "[^a-zA-Z0-9_=,;&*-.\\n\\r]"
def sanitizeK6ScriptPathRegex = "[^a-zA-Z0-9_./-]"

// Populated by the 'Qase Run Summary' stage for the Slack notification.
def qaseRunTotal
def qaseRunPassed
def qaseRunFailed
def qaseRunPassPercent
def qaseRunUrl

pipeline {
  agent { label agentLabel }

  environment {
    // Define environment variables here.  These are available throughout the pipeline.
    imageName = 'dartboard'
    harvesterKubeconfig = 'harvester.kubeconfig'
    templateDartFile = 'template-dart.yaml'
    renderedDartFile = 'rendered-dart.yaml'
    envFile = ".env"
    DEFAULT_PROJECT_NAME = "${JOB_NAME.split('/').last()}-${BUILD_NUMBER}"
    accessDetailsLog = 'access-details.log'
    summaryHtmlFile = 'summary.html'

    // renovate: datasource=docker depName=amazon/aws-cli
    AWS_CLI_VERSION = '2.34.22'
    // renovate: datasource=docker depName=amazon/aws-cli digestVersion=2.34.22
    AWS_CLI_DIGEST = 'sha256:96516991f34382a7667f3e59db2262f209a86ab4ea371e22242f0898a4e9ffc8'
  }

  // No parameters block here—JJB YAML defines them.
  // Expected params include (in addition to the shared deploy params): CLEANUP (boolean,
  // default true), QASE_TESTOPS_PROJECT, QASE_TESTOPS_RUN_ID.

  stages {
    stage('Initialize & Checkout') {
      steps {
        script {
          // Use useWithCredentials to securely handle the PEM key
          property.useWithCredentials(['AWS_SSH_PEM_KEY_NAME', 'AWS_SSH_PEM_KEY']) {
            // Initialize variables with fallback logic
            finalSSHPemKey = params.SSH_PEM_KEY ? params.SSH_PEM_KEY : env.AWS_SSH_PEM_KEY
            def sshKeyNameFromCreds = env.AWS_SSH_PEM_KEY_NAME ? env.AWS_SSH_PEM_KEY_NAME.trim().split('\\.')[0] : null
            finalSSHKeyName = params.SSH_KEY_NAME ? params.SSH_KEY_NAME : sshKeyNameFromCreds

            // This pipeline always provisions infrastructure (deploy), so SSH keys are always required.
            if (!finalSSHPemKey || !finalSSHKeyName) {
              error("This pipeline requires SSH keys. Please provide both 'SSH_PEM_KEY' and 'SSH_KEY_NAME' parameters, or ensure corresponding credentials (AWS_SSH_PEM_KEY, AWS_SSH_PEM_KEY_NAME) are available.")
            }

            sh """
            echo '---- SSH KEY NAME ---'
            echo ${finalSSHKeyName}
            """
            scmWorkspace = project.checkout(repository: params.REPO, branch: params.BRANCH, target: 'dartboard')
          }
        }
      }
    }

    stage('Configure and Build') {
      steps {
        dir('dartboard') {
          script {
            property.useWithProperties(['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY']) {
              echo "Storing env in file"
              sh "printenv | egrep '^(ARM_|CATTLE_|ADMIN|USER|DO|RANCHER_|AWS_|DEBUG|LOGLEVEL|DEFAULT_|OS_|DOCKER_|CLOUD_|KUBE|BUILD_NUMBER|AZURE|TEST_|SLACK_|harvester|TF_).*=.+' | sort > ${env.envFile}"
              if (params.EXTRA_ENV_VARS) {
                sh "echo \"${params.EXTRA_ENV_VARS}\" >> ${env.envFile}"
              }
              sh "docker build -t ${env.imageName}:latest ."
            }
          }
        }
      }
    }

    stage('Start Service Container') {
      steps {
        script {
          generatedNames = generate.names()
          runningContainerName = "${generatedNames.container}-service"
          sh """
            docker run -d --rm --name ${runningContainerName} \\
              -v ${pwd()}/dartboard:/dartboard \\
              --workdir /dartboard \\
              --env-file dartboard/${env.envFile} \\
              --entrypoint='' \\
              --user=\$(id -u) \\
              ${env.imageName}:latest sleep infinity
          """
        }
      }
    }

    stage('Set Build Description') {
      steps {
        script {
          // Use yq inside the running service container to parse the rancher_version from the DART file contents
          def rancherVersion = sh(
            script: "docker exec ${runningContainerName} sh -c 'echo \"\$1\" | yq .chart_variables.rancher_version' -- '${params.DART_FILE}'",
            returnStdout: true
          ).trim()

          if (rancherVersion && rancherVersion != 'null') {
            currentBuild.description = "Rancher v${rancherVersion}"
          }
        }
      }
    }

    stage('Setup SSH Keys') {
      steps {
        script {
          def sshScript = """
            echo "Writing SSH keys to container..."
            # The PEM key is passed via standard input to avoid issues with special characters
            echo "\${1}" | base64 -d > /dartboard/${finalSSHKeyName}.pem
            chmod 600 /dartboard/${finalSSHKeyName}.pem

            echo "Generating public key from PEM key..."
            ssh-keygen -y -f /dartboard/${finalSSHKeyName}.pem > /dartboard/${finalSSHKeyName}.pub
            chmod 644 /dartboard/${finalSSHKeyName}.pub

            echo "VERIFICATION FOR PUB KEY:"
            cat /dartboard/${finalSSHKeyName}.pub
          """
          sh "docker exec --user=\$(id -u) ${runningContainerName} sh -c '${sshScript}' -- '${finalSSHPemKey}'"
        }
      }
    }

    stage('Determine Project Name') {
      steps {
        script {
          // This pipeline always creates a fresh deployment (no DEPLOYMENT_ID targeting);
          // use dartboard-choice.groovy to operate on an existing one.
          def projectNameFromDart = sh(
            script: "docker exec ${runningContainerName} sh -c 'echo \"\$1\" | yq .tofu_variables.project_name' -- '${params.DART_FILE}'",
            returnStdout: true
          ).trim()

          // Override DEFAULT_PROJECT_NAME if a valid one is found in the DART file
          if (projectNameFromDart && projectNameFromDart != 'null' && !projectNameFromDart.startsWith('$')) {
            echo "Using project_name from DART file: ${projectNameFromDart}"
            finalProjectName = projectNameFromDart
          } else {
            finalProjectName = env.DEFAULT_PROJECT_NAME
            echo "Using default project name: ${env.DEFAULT_PROJECT_NAME}"
          }
        }
      }
    }

    stage('Prepare Parameter Files') {
      steps {
        script {
          property.useWithCredentials(['ADMIN_PASSWORD', 'USER_PASSWORD']) {
            // Render the Dart file using Groovy string replacement
            def dartTemplate = params.DART_FILE
            def renderedDart = dartTemplate.replaceAll('\\$\\{HARVESTER_KUBECONFIG\\}', "/dartboard/${env.harvesterKubeconfig}")
                                            .replaceAll('\\$\\{SSH_KEY_NAME\\}', "/dartboard/${finalSSHKeyName}")
                                            .replaceAll('\\$\\{PROJECT_NAME\\}', finalProjectName)
                                            .replaceAll('\\$\\{ADMIN_PASSWORD\\}', ADMIN_PASSWORD)
                                            .replaceAll('\\$\\{USER_PASSWORD\\}', USER_PASSWORD)

            // Use docker exec to write all parameter files to the container
            sh """
              docker exec --user=\$(id -u) --workdir /dartboard ${runningContainerName} sh -c '''
                echo "Writing parameter files to container using here-documents to preserve special characters..."

                # Write HARVESTER_KUBECONFIG to harvester.kubeconfig
                cat <<'EOF' > ${env.harvesterKubeconfig}
${params.HARVESTER_KUBECONFIG}
EOF
                # Write the rendered DART file
                cat <<'EOF' > ${env.renderedDartFile}
${renderedDart}
EOF
                echo "DUMPING INPUT FILES FOR MANUAL VERIFICATION"
                echo "---- harvester.kubeconfig ----"
                cat ${env.harvesterKubeconfig}
                echo "---- rendered-dart.yaml ----"
                cat ${env.renderedDartFile}
              '''
            """
          }
        }
      }
    }

    stage('Deploy') {
      steps {
        script {
          echo "Executing 'dartboard deploy'..."
          def dartboardCmd = """
            docker exec -t --user=\$(id -u) --workdir /dartboard ${runningContainerName} dartboard \\
              --dart ${env.renderedDartFile} deploy
          """
          retry(3) {
            sh dartboardCmd
          }
        }
      }
      post {
        success {
          script {
            sh """
              docker exec --user=\$(id -u) --workdir /dartboard ${runningContainerName} sh -c '''
                  echo "Creating archives..."
                  tofuMainDir=\$(yq ".tofu_main_directory" ${env.renderedDartFile})
                  tfstateDir="\${tofuMainDir}/terraform.tfstate.d/"
                  configDirPath=\$(find . -type d -name "*_config" | head -n 1)

                  if [ -d "\${tfstateDir}" ]; then
                    echo "Creating OpenTofu state archive from '\${tfstateDir}'..."
                    archiveName="tfstate-${finalProjectName}.zip"
                    (cd "\${tfstateDir}" && zip -r "/dartboard/\${archiveName}" "${finalProjectName}")
                  else
                    echo "Could not find OpenTofu state directory at '\${tfstateDir}', skipping archive creation."
                  fi

                  if [ -n "\${configDirPath}" ] && [ -d "\${configDirPath}" ]; then
                    echo "Creating config archive from \${configDirPath}..."
                    archiveName="\$(basename \${configDirPath}).zip"
                    (cd \${configDirPath} && zip -r "/dartboard/\${archiveName}" ./)
                  fi
              '''
            """
          }
        }
        failure {
          script {
            echo "Deploy failed. Running dartboard destroy..."
            sh """
              docker exec --user=\$(id -u) --workdir /dartboard ${runningContainerName} dartboard \\
              --dart ${env.renderedDartFile} destroy
            """
          }
        }
      }
    }

    stage('Load') {
      steps {
        script {
          echo "Executing 'dartboard load'..."
          def dartboardCmd = """
            docker exec -t --user=\$(id -u) --workdir /dartboard ${runningContainerName} dartboard \\
              --dart ${env.renderedDartFile} load
          """
          def exitCode = sh(script: dartboardCmd, returnStatus: true)
          if (exitCode == K6ThresholdsHaveFailed) {
            echo "WARNING: k6 thresholds were crossed, but all iterations completed. Marking build as UNSTABLE."
            currentBuild.result = 'UNSTABLE'
          } else if (exitCode != 0) {
            error("'dartboard load' failed with exit code ${exitCode}")
          }
        }
      }
    }

    stage('Get Access Details') {
      steps {
        script {
          sh "docker exec --user=\$(id -u) --workdir /dartboard ${runningContainerName} sh -c 'dartboard --dart ${env.renderedDartFile} get-access > ${env.accessDetailsLog}'"

          def accessLogContent = sh(script: "docker exec ${runningContainerName} cat /dartboard/${env.accessDetailsLog}", returnStdout: true)
          echo "---- Access Details ----"
          echo accessLogContent

          // Needed by the Qase test suite stage below (BASE_URL/KUBECONFIG for k6).
          def matcher = accessLogContent =~ /(?m)^\s*Rancher UI:\s*(https?:\/\/[^ :]+)/
          if (matcher.find()) {
            baseURL = matcher.group(1).trim()
            echo "Found Rancher URL: ${baseURL}"
          } else {
            echo "Warning: Could not find 'Rancher UI' in ${env.accessDetailsLog}"
          }

          def configDirName = sh(
            script: "docker exec ${runningContainerName} sh -c 'find . -maxdepth 1 -type d -name \"*_config\" | head -n 1'",
            returnStdout: true
          ).trim().replaceFirst('^\\./', '')

          if (configDirName) {
            kubeconfigContainerPath = "/dartboard/${configDirName}/upstream.yaml"
            echo "Using kubeconfig at: ${kubeconfigContainerPath}"
          } else {
            echo "Warning: Could not locate a '*_config' directory for kubeconfig."
          }
        }
      }
    }

    stage('Gather Qase Test Cases') {
      steps {
        dir('dartboard') {
          script {
            def safeRunID = (params.QASE_TESTOPS_RUN_ID ?: "").replaceAll("[^0-9]", "")
            def safeProject = (params.QASE_TESTOPS_PROJECT ?: "").replaceAll(sanitizeCharacterRegex, "")

            withCredentials([string(credentialsId: "QASE_AUTOMATION_TOKEN", variable: "QASE_TESTOPS_API_TOKEN")]) {
              sh """
                docker exec --user=\$(id -u) --workdir /dartboard \\
                  -e QASE_TESTOPS_API_TOKEN \\
                  -e QASE_TESTOPS_PROJECT="${safeProject}" \\
                  ${runningContainerName} qase-k6-cli gather -runID "${safeRunID}" > test_cases.json
              """
            }
            sh "cat test_cases.json"

            def countStr = sh(
              script: "docker exec --workdir /dartboard ${runningContainerName} yq '. | length' test_cases.json",
              returnStdout: true
            ).trim()

            numTestCases = countStr.isInteger() ? countStr.toInteger() : 0

            if (numTestCases == 0) {
              echo "No test cases found with 'AutomationTestName' custom field in Run ID ${params.QASE_TESTOPS_RUN_ID}."
            }
          }
        }
      }
    }

    stage('Run Qase Test Suite') {
      when { expression { return numTestCases > 0 } }
      steps {
        dir('dartboard') {
          script {
            // Iterate over each gathered test case
            for (int index = 0; index < numTestCases; index++) {
              // Extract test case details using yq.
              // We output ID, Title, ScriptPath, and then parameters as key=::=value lines
              def caseDataLines = sh(script: """
                  docker exec --workdir /dartboard ${runningContainerName} yq -r ".[${index}] | (.id, .title, .automation_test_name, (.parameters // {} | to_entries | .[] | \\"\\(.key)=::=\\(.value)\\"))" test_cases.json
              """, returnStdout: true).trim().readLines()

              if (caseDataLines.size() < 3) {
                  echo "Warning: Could not parse test case at index ${index}"
                  continue
              }

              def caseId = caseDataLines[0]
              def caseTitle = caseDataLines[1]
              def scriptPath = caseDataLines[2]
              def parameters = [:]

              if (caseDataLines.size() > 3) {
                  caseDataLines[3..-1].each { line ->
                      def parts = line.split('=::=', 2)
                      if (parts.length == 2) {
                          parameters[parts[0]] = parts[1]
                      }
                  }
              }

              echo "-------------------------------------------------------"
              echo "Processing Case ID: ${caseId}"
              echo "Title: ${caseTitle}"
              echo "Script: ${scriptPath}"
              echo "Parameters: ${parameters}"
              echo "-------------------------------------------------------"

              def safeScriptPath = (scriptPath ?: "").replaceAll(sanitizeK6ScriptPathRegex, "")
              if (!safeScriptPath || safeScriptPath != scriptPath || !safeScriptPath.endsWith('.js') || safeScriptPath.contains('..')) {
                error("Invalid k6 script path for Case ${caseId}: ${scriptPath}")
              }
              if (!fileExists(safeScriptPath)) {
                error("k6 script not found for Case ${caseId}: ${safeScriptPath}")
              }

              // Sanitize project name to prevent shell injection in filenames
              def safeProject = (params.QASE_TESTOPS_PROJECT ?: "").replaceAll(sanitizeCharacterRegex, "")

              // 1. Prepare Environment for this specific test case
              // Use index to ensure uniqueness for file names when multiple parameter combinations exist for the same case ID
              def basename = safeScriptPath.tokenize('/').last().replaceAll("\\.js", "")
              def envFile = "k6-${basename}-${safeProject}-${caseId}-${index}.env"
              def k6ReportPrefix = "k6-${basename}-${safeProject}-${caseId}-${index}"
              def summaryLog = "k6-summary-${safeProject}-${caseId}-${index}-params.log"
              def summaryJson = "${k6ReportPrefix}-summary.json"
              def htmlReport = "${k6ReportPrefix}-summary.html"
              def webDashboardReport = "k6-web-dashboard-${safeProject}-${caseId}-${index}.html"
              def safeK6Env = params.K6_ENV ? params.K6_ENV.replaceAll(sanitizeK6EnvRegex, "") : ""

              // Construct environment variables content.
              // We set QASE_TEST_CASE_ID for the reporter
              def envContent = ""

              // Handle parameters required by the test case
              parameters.each { paramName, paramValue ->
                 // Check if the Jenkins job has this parameter defined, otherwise use the value from Qase
                 def finalValue = params[paramName] ?: paramValue
                 // Sanitize value to prevent newlines breaking the env file format
                 finalValue = finalValue.toString().replaceAll("[\r\n]", "")
                 envContent += "${paramName}=${finalValue}\n"
              }

              envContent += """
K6_NO_USAGE_REPORT=true
K6_TEST=${safeScriptPath}
K6_REPORT_PREFIX=${k6ReportPrefix}
BASE_URL=${baseURL ?: ''}
KUBECONFIG=${kubeconfigContainerPath ?: ''}
QASE_TESTOPS_PROJECT=${params.QASE_TESTOPS_PROJECT}
QASE_TESTOPS_RUN_ID=${params.QASE_TESTOPS_RUN_ID}
QASE_TEST_CASE_ID=${caseId}
K6_SUMMARY_JSON_FILE=${summaryJson}
K6_SUMMARY_HTML_FILE=${htmlReport}
K6_WEB_DASHBOARD=true
K6_WEB_DASHBOARD_EXPORT=${webDashboardReport}
${safeK6Env}
"""

              writeFile file: envFile, text: envContent

              echo "Environment file for Case ${caseId} (index ${index}):"
              echo envContent

              // 2. Run k6 in the already-running service container (same one used for deploy/load,
              // so BASE_URL/KUBECONFIG above already point at valid in-container paths).
              try {
                  sh """
                    docker exec --user=\$(id -u) --workdir /dartboard ${runningContainerName} sh -c '''
                        set -o allexport
                        . "/dartboard/${envFile}"
                        set +o allexport
                        set -o pipefail

                        # Prepare a script copy with handleSummary disabled to allow Native Dashboard generation
                        TEST_DIR=\$(dirname "\$K6_TEST")
                        TEST_FILE=\$(basename "\$K6_TEST")
                        MODIFIED_TEST="\${TEST_DIR}/native_\${TEST_FILE}"

                        cp "\$K6_TEST" "\$MODIFIED_TEST"

                        echo "Running k6 script (Native Dashboard Mode): \$MODIFIED_TEST"
                        k6 run --no-color "\$MODIFIED_TEST" | tee "${summaryLog}" || {
                            K6_EXIT_CODE=\$?
                            if [ "\${K6_EXIT_CODE}" -ne 99 ]; then
                                exit \${K6_EXIT_CODE}
                            fi
                            echo "k6 finished with thresholds exceeded (exit code 99), continuing..."
                        }

                        # 3. Generate Custom Reports (k6-reporter, custom JUnit) from the JSON summary
                        echo "Generating custom reports from ${summaryJson}..."
                        k6 run --no-color -e K6_SUMMARY_JSON_FILE="/dartboard/${summaryJson}" k6/generic/report_generator.js > /dev/null
                    '''
                  """
              } catch (Exception e) {
                  echo "k6 run failed for case ${caseId}, but continuing to report failure/partial results."
              }
              sh "ls -al"

              // 4. Report to Qase
              withCredentials([string(credentialsId: "QASE_AUTOMATION_TOKEN", variable: "QASE_TESTOPS_API_TOKEN")]) {
                  sh """
                    docker exec --user=\$(id -u) --workdir /dartboard \\
                        -e QASE_TESTOPS_API_TOKEN \\
                        ${runningContainerName} sh -c '''
                            echo "Reporting results for Case ${caseId}..."
                            set -o allexport
                            . "/dartboard/${envFile}"
                            set +o allexport
                            if [ -f "${summaryJson}" ]; then
                                qase-k6-cli report
                            else
                                echo "Summary JSON not found, skipping report for ${caseId}"
                            fi
                        '''
                  """
              }
            }
          }
        }
      }
    }

    stage('Qase Run Summary') {
      when { expression { return numTestCases > 0 } }
      steps {
        dir('dartboard') {
          script {
            def safeRunID = (params.QASE_TESTOPS_RUN_ID ?: "").replaceAll("[^0-9]", "")
            def safeProject = (params.QASE_TESTOPS_PROJECT ?: "").replaceAll(sanitizeCharacterRegex, "")

            withCredentials([string(credentialsId: "QASE_AUTOMATION_TOKEN", variable: "QASE_TESTOPS_API_TOKEN")]) {
              def summaryOutput = sh(script: """
                docker exec --workdir /dartboard \\
                  -e QASE_TESTOPS_API_TOKEN \\
                  -e QASE_TESTOPS_PROJECT="${safeProject}" \\
                  ${runningContainerName} qase-k6-cli summary -runID "${safeRunID}"
              """, returnStdout: true).trim()

              echo summaryOutput

              summaryOutput.readLines().each { line ->
                def parts = line.split('=', 2)
                if (parts.length != 2) {
                  return
                }
                switch (parts[0]) {
                  case 'QASE_RUN_TOTAL': qaseRunTotal = parts[1]; break
                  case 'QASE_RUN_PASSED': qaseRunPassed = parts[1]; break
                  case 'QASE_RUN_FAILED': qaseRunFailed = parts[1]; break
                  case 'QASE_RUN_PASS_PERCENT': qaseRunPassPercent = parts[1]; break
                  case 'QASE_RUN_URL': qaseRunUrl = parts[1]; break
                }
              }
            }
          }
        }
      }
    }

    stage('Destroy Deployment') {
      steps {
        script {
          // CLEANUP defaults to true; explicitly set it to false to leave the deployment up
          // for later inspection/manual destroy via dartboard-choice.groovy + DEPLOYMENT_ID
          // (state was already archived to S3 by the Deploy stage above).
          if (params.CLEANUP == false) {
            echo "CLEANUP is disabled; leaving deployment '${finalProjectName}' running."
            return
          }

          echo "Running dartboard destroy..."
          def dartboardCmd = """
            docker exec -t --user=\$(id -u) --workdir /dartboard ${runningContainerName} dartboard \\
              --dart ${env.renderedDartFile} destroy
          """
          retry(3) {
            sh dartboardCmd
          }
        }
      }
    }
  }

  post {
    always {
      script {
        echo "Generating build summary..."
        // Artifacts are on the agent workspace via the volume mount, no copy needed.

        dir('dartboard') {
          def accessDetails = fileExists(env.accessDetailsLog) ? parseToHTML(readFile(env.accessDetailsLog)) : "Access details log not found."

          // We need to find the config dir path again on the agent for the summary link
          try {
              configDirPath = sh(script: "find . -type d -name '*_config' | head -n 1", returnStdout: true).trim()
          } catch (e) {
              configDirPath = null
          }

          def qaseSummaryHtml = ""
          if (qaseRunTotal) {
            def qaseLink = qaseRunUrl ? "<a href='${qaseRunUrl}' target='_blank'>View Run in Qase</a>" : ""
            qaseSummaryHtml = "<h2>Qase Test Run</h2><p>${qaseRunPassed}/${qaseRunTotal} passed (${qaseRunPassPercent}%), ${qaseRunFailed} failed. ${qaseLink}</p>"
          }

          // Generate the HTML content
          def htmlContent = """
            <html>
              <head>
                <title>Build Summary for ${env.JOB_NAME} #${env.BUILD_NUMBER}</title>
                <style>
                  body { font-family: sans-serif; background-color: #1e1e1e; color: #d4d4d4; }
                  h1, h2 { color: #d4d4d4; }
                  h2 { border-bottom: 1px solid #3c3c3c; padding-bottom: 5px; }
                  pre { background-color: #252526; border: 1px solid #3c3c3c; padding: 10px; white-space: pre-wrap; word-wrap: break-word; color: #ce9178; }
                  ul { list-style-type: none; padding-left: 0; }
                  li { margin-bottom: 10px; }
                  a { color: #3794ff; text-decoration: none; }
                  a:hover { text-decoration: underline; }
                  i { color: #808080; }
                </style>
              </head>
              <body>
                <h1>Build Summary: ${env.JOB_NAME} #${env.BUILD_NUMBER}</h1>

                <h2>Cluster Access Details</h2>
                <pre>${accessDetails}</pre>

                ${qaseSummaryHtml}

                <h2>Downloads</h2>
                <ul>
                  ${fileExists(env.renderedDartFile) ? "<li><a href='${env.BUILD_URL}artifact/dartboard/${env.renderedDartFile}' download target='_blank'>Download Rendered DART File (${env.renderedDartFile})</a></li>" : ""}
                  ${configDirPath ? "<li><a href='${env.BUILD_URL}artifact/dartboard/${configDirPath.split('/').last()}.zip' download target='_blank'>Download Cluster Configs (${configDirPath.split('/').last()}.zip)</a></li>" : ""}
                  ${fileExists("tfstate-${finalProjectName}.zip") ? "<li><a href='${env.BUILD_URL}artifact/dartboard/tfstate-${finalProjectName}.zip' download target='_blank'>Download OpenTofu State (tfstate-${finalProjectName}.zip)</a></li>" : ""}
                </ul>
                <p><i>See 'Archived Artifacts' for all generated files, including tofu state.</i></p>
              </body>
            </html>
          """
          writeFile file: env.summaryHtmlFile, text: htmlContent

          property.useWithCredentials(['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY']) {
            echo "Uploading build artifacts to S3..."
            // Copy files from the workspace (which is mounted into the container) to the temp s3 dir
            def s3ArtifactsDir = "s3-upload-artifacts"
            sh "mkdir -p ${s3ArtifactsDir}"
            // Find the config zip file name first
            def configZipFile = sh(script: "find . -maxdepth 1 -name '*_config.zip' -exec basename {} \\;", returnStdout: true).trim()

            // Explicitly copy only the artifacts used for the build summary and S3 upload
            sh """
              cp ${env.renderedDartFile} ${s3ArtifactsDir}/ 2>/dev/null || true
              cp tfstate-${finalProjectName}.zip ${s3ArtifactsDir}/ 2>/dev/null || true
              cp ${env.accessDetailsLog} ${s3ArtifactsDir}/ 2>/dev/null || true
              if [ -n "${configZipFile}" ]; then cp ${configZipFile} ${s3ArtifactsDir}/ 2>/dev/null || true; fi
            """

            // Run the aws-cli container to upload the files
            sh script: """
              docker run --rm \\
                -v "${pwd()}/${s3ArtifactsDir}:/artifacts" \\
                -e AWS_ACCESS_KEY_ID \\
                -e AWS_SECRET_ACCESS_KEY \\
                -e AWS_S3_REGION="${params.S3_BUCKET_REGION}" \\
                amazon/aws-cli:${env.AWS_CLI_VERSION}@${env.AWS_CLI_DIGEST} s3 cp /artifacts "s3://${params.S3_BUCKET_NAME}/${finalProjectName}/" --recursive
            """, returnStatus: true

            // Clean up the temporary directory
            sh "rm -rf ${s3ArtifactsDir}"
          }

          echo "Sending Slack notification..."
          withEnv([
            "QASE_RUN_TOTAL=${qaseRunTotal ?: ''}",
            "QASE_RUN_PASSED=${qaseRunPassed ?: ''}",
            "QASE_RUN_FAILED=${qaseRunFailed ?: ''}",
            "QASE_RUN_PASS_PERCENT=${qaseRunPassPercent ?: ''}",
            "QASE_RUN_URL=${qaseRunUrl ?: ''}"
          ]) {
            sh "chmod +x CI/slack-notification.sh"
            sh "CI/slack-notification.sh ${currentBuild.currentResult}"
          }
        }

        echo "Archiving build artifacts..."
        archiveArtifacts artifacts: """
            dartboard/*.html,
            dartboard/*.json,
            dartboard/**/rendered-dart.yaml,
            dartboard/*.log,
            dartboard/*.zip
        """.trim(), fingerprint: true

        // Cleanup Docker resources with explicit logging
        try {
          if (runningContainerName) {
            echo "Attempting to remove service container: ${runningContainerName}"
            sh "docker rm -f ${runningContainerName}"
          }
        } catch (e) {
          echo "Could not remove container '${runningContainerName}'. It may have already been removed or never started. Details: ${e.message}"
        }
        try {
          echo "Attempting to remove image: ${env.imageName}:latest"
          sh "docker rmi -f ${env.imageName}:latest"
          echo "Attempting to remove image: amazon/aws-cli:${env.AWS_CLI_VERSION}@${env.AWS_CLI_DIGEST}"
          sh "docker rmi amazon/aws-cli:${env.AWS_CLI_VERSION}@${env.AWS_CLI_DIGEST}"
        } catch (e) {
          echo "Could not remove a Docker image. It may have already been removed or was never present. Details: ${e.message}"
        }
      }
    }
    cleanup {
      // Clean up large files from the workspace to save disk space on the agent.
      // These are not part of the archived artifacts but remain in the workspace.
      echo "Cleaning up workspace..."
      dir('dartboard') {
        // Use find and xargs for more robust and efficient cleanup of non-artifact files and directories.
        sh """
          set -x
          echo "Removing large source and cache directories..."
          rm -rf charts/ docs/ internal/ k6/ tofu/ cmd/ scripts/ darts/

          echo "Removing other non-artifact files..."
          find . -maxdepth 1 -type f \\
            -not -name '*.html' -not -name '*.json' -not -name '*.log' -not -name '*.zip' \\
            -not -name 'rendered-dart.yaml' -not -name 'Jenkinsfile' -delete
        """
      }
    }
  }
}