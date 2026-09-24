#!groovy
// Fixed flow: deploy -> load -> Qase-driven k6 suite -> optional destroy.
// Infrastructure commands are delegated to dartboard-choice and Qase execution
// is delegated to dartboard-qase-k6-runner so their implementations remain the source of truth.
@Library('qa-jenkins-library') _

def deploymentId
def deploymentCreated = false
def qaseK6Build
// Temporary hardcode for STABILITY_DELAY
def stabilityDelay = 0

def downstreamResult(buildResult, jobName) {
  if (buildResult?.number) {
    echo "${jobName} build #${buildResult.number} completed: ${buildResult.result ?: 'UNKNOWN'}"
  }
  if (buildResult?.result == 'SUCCESS') {
    return
  }
  if (buildResult?.result == 'UNSTABLE') {
    currentBuild.result = 'UNSTABLE'
    return
  }
  error("${jobName} build #${buildResult?.number} failed with result: ${buildResult?.result ?: 'UNKNOWN'}")
}

def choiceParameters(command, deploymentIdValue) {
  return [
    string(name: 'REPO', value: params.REPO ?: ''),
    string(name: 'BRANCH', value: params.BRANCH ?: ''),
    string(name: 'DART_FILE', value: params.DART_FILE ?: ''),
    string(name: 'HARVESTER_KUBECONFIG', value: params.HARVESTER_KUBECONFIG ?: ''),
    string(name: 'DARTBOARD_COMMAND', value: command),
    string(name: 'DEPLOYMENT_ID', value: deploymentIdValue ?: ''),
    string(name: 'SSH_PEM_KEY', value: params.SSH_PEM_KEY ?: ''),
    string(name: 'SSH_KEY_NAME', value: params.SSH_KEY_NAME ?: ''),
    string(name: 'EXTRA_ENV_VARS', value: params.EXTRA_ENV_VARS ?: ''),
    string(name: 'S3_BUCKET_NAME', value: params.S3_BUCKET_NAME ?: ''),
    string(name: 'S3_BUCKET_REGION', value: params.S3_BUCKET_REGION ?: '')
  ]
}

pipeline {
  agent { label 'jenkins-qa-jenkins-agent' }
  stages {
    // stage('Deploy') {
    //   steps {
    //     script {
    //       def deployBuild = build(
    //         job: 'dartboard-choice',
    //         parameters: choiceParameters('deploy', ''),
    //         propagate: false,
    //         wait: true
    //       )
    //       downstreamResult(deployBuild, 'Deploy')

    //       // Fetch rendered DART artifact containing resolved deployment variables
    //       sh 'rm -rf dartboard/rendered-dart.yaml rendered-dart.yaml'
    //       copyArtifacts(
    //         filter: 'dartboard/rendered-dart.yaml',
    //         projectName: 'dartboard-choice',
    //         selector: specific("${deployBuild.number}")
    //       )

    //       def artifactPath = fileExists('dartboard/rendered-dart.yaml') ? 'dartboard/rendered-dart.yaml' :
    //                          (fileExists('rendered-dart.yaml') ? 'rendered-dart.yaml' : null)
    //       if (!artifactPath) {
    //         error('Deploy artifact rendered-dart.yaml was not found in the workspace.')
    //       }

    //       // Extract project_name directly
    //       def dartContent = readFile(artifactPath)
    //       def projectLine = dartContent.readLines().find { it.trim().startsWith('project_name:') }
    //       deploymentId = projectLine ? projectLine.split(':', 2)[1].split('#')[0].replaceAll(/["'\s]/, '') : ''

    //       // Store and display initial project_name as deploymentId in environment
    //       env.DEPLOYMENT_ID = deploymentId
    //       echo "Resolved deployment ID: ${deploymentId}"

    //       if (!deploymentId || deploymentId.startsWith('$')) {
    //         error("Deploy artifact did not contain a resolved project_name as deploymentId: ${deploymentId}")
    //       }

    //       // TODO: Improve description
    //       currentBuild.description = "Deployment ${deploymentId}"
    //       deploymentCreated = true
    //     }
    //   }
    // }

    // stage('Load') {
    //   steps {
    //     script {
    //       try {
    //         // Retrieve deployment ID from environment or fallback script binding
    //         def targetDeploymentId = env.DEPLOYMENT_ID ?: deploymentId
    //         if (!targetDeploymentId) {
    //           error('Cannot execute Load stage: deploymentId is empty.')
    //         }
    //         echo "Executing Load stage for deployment '${targetDeploymentId}'..."
    //         def loadBuild = build(
    //           job: 'dartboard-choice',
    //           parameters: choiceParameters('load', targetDeploymentId),
    //           propagate: false,
    //           wait: true
    //         )
    //         downstreamResult(loadBuild, 'Load')
    //       } catch (Throwable t) {
    //         echo "Load stage failed with error: ${t.class.name}: ${t.message}"
    //         throw t
    //       }
    //     }
    //   }
    // }

    // stage('Stabilize') {
    //   when {
    //     // expression { params.STABILITY_DELAY?.toString()?.isInteger() && params.STABILITY_DELAY.toInteger() > 0 }
    //     expression { stabilityDelay > 0 }
    //   }
    //   steps {
    //     //echo "Pausing for ${params.STABILITY_DELAY} minutes before starting tests..."
    //     //sleep time: params.STABILITY_DELAY.toInteger(), unit: 'MINUTES'
    //     echo "Pausing for ${stabilityDelay} minutes before starting tests..."
    //     sleep time: stabilityDelay, unit: 'MINUTES'
    //   }
    // }

    // stage('Test') {
    //   steps {
    //     script {
    //       try {
    //         // Forward deployment and target configuration to the k6 test runner
    //         def targetDeploymentId = env.DEPLOYMENT_ID ?: deploymentId
    //         if (!targetDeploymentId) {
    //           error('Cannot execute Qase Test Suite: deploymentId is empty.')
    //         }
    //         echo "Executing Qase Test Suite for deployment '${targetDeploymentId}'..."
    //         qaseK6Build = build(
    //           job: 'dartboard-qase-k6-runner',
    //           parameters: [
    //             string(name: 'REPO', value: params.REPO ?: ''),
    //             string(name: 'BRANCH', value: params.BRANCH ?: ''),
    //             string(name: 'DEPLOYMENT_ID', value: targetDeploymentId),
    //             string(name: 'S3_BUCKET_NAME', value: params.S3_BUCKET_NAME ?: ''),
    //             string(name: 'S3_BUCKET_REGION', value: params.S3_BUCKET_REGION ?: ''),
    //             string(name: 'QASE_TESTOPS_PROJECT', value: params.QASE_TESTOPS_PROJECT ?: ''),
    //             string(name: 'QASE_TESTOPS_RUN_ID', value: params.QASE_TESTOPS_RUN_ID ?: ''),
    //             string(name: 'K6_ENV', value: params.K6_ENV ?: '')
    //           ],
    //           propagate: false,
    //           wait: true
    //         )
    //         downstreamResult(qaseK6Build, 'Run Qase Test Suite')
    //       } catch (Throwable t) {
    //         echo "Qase Test Suite stage failed with error: ${t.class.name}: ${t.message}"
    //         throw t
    //       }
    //     }
    //   }
    // }

    stage('Mock Data') {
      steps {
        script {
          env.RANCHER_VERSION = 'v2.10.2'
          env.KUBERNETES_VERSION = 'v1.31.2'

          sh 'mkdir -p dartboard'
          writeFile file: 'dartboard/qase-runstats.env', text: '''QASE_RUN_TOTAL=10
QASE_RUN_PASSED=9
QASE_RUN_FAILED=1
QASE_RUN_EXCEEDED_THRESHOLDS=0
QASE_RUN_URL=https://app.qase.io/run/DEMO/dashboard/12345
'''
        }
      }
    }
  }

  post {
    always {
      script {
        // if (!deploymentCreated) {
        //   echo 'Deployment was not created; skipping cleanup.'
        // } else if (!params.DESTROY) {
        //   echo "DESTROY is disabled; leaving deployment '${deploymentId}' running."
        // } else {
        //   def targetDeploymentId = env.DEPLOYMENT_ID ?: deploymentId
        //   echo "Destroying deployment '${targetDeploymentId}'..."
        //   try {
        //     def destroyBuild = build(
        //       job: 'dartboard-choice',
        //       parameters: choiceParameters('destroy', targetDeploymentId),
        //       propagate: false,
        //       wait: true
        //     )
        //     if (destroyBuild?.number) {
        //       echo "Destroy build #${destroyBuild.number} completed: ${destroyBuild.result ?: 'UNKNOWN'}"
        //     }
        //     if (destroyBuild?.result != 'SUCCESS') {
        //       currentBuild.result = 'UNSTABLE'
        //     }
        //   } catch (Exception e) {
        //     currentBuild.result = 'UNSTABLE'
        //     echo "Failed to trigger or complete Destroy build: ${e.message}"
        //   }
        // }

        // Fetch and validate test run statistics
        if (params.SLACK_NOTIFICATION) {
          if (qaseK6Build?.number) {
            sh 'rm -rf dartboard/qase-runstats.env'
            try {
              copyArtifacts(
                filter: 'dartboard/qase-runstats.env',
                projectName: 'dartboard-qase-k6-runner',
                selector: specific("${qaseK6Build.number}")
              )
            } catch (e) {
              echo "Qase run stats artifact was unavailable: ${e.message}"
            }
          } else {
            echo 'dartboard-qase-k6-runner did not start; checking for existing or mock run stats.'
          }

          withCredentials([
            string(credentialsId: 'DARTBOARD_SLACK_BOT_TOKEN', variable: 'DARTBOARD_SLACK_BOT_TOKEN'),
            string(credentialsId: 'DARTBOARD_SLACK_CHANNEL', variable: 'DARTBOARD_SLACK_CHANNEL')
          ]) {
            def notificationStatus = sh(
              script: """
                # Validate expected format and key constraints before sourcing
                if [ -f dartboard/qase-runstats.env ]; then
                  if [ "\$(wc -l < dartboard/qase-runstats.env)" -eq 5 ] && \\
                    grep -Eq '^QASE_RUN_TOTAL=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_PASSED=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_FAILED=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_EXCEEDED_THRESHOLDS=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_URL=https://app\\.qase\\.io/run/[^[:space:]/]+/dashboard/[0-9]+\$' dartboard/qase-runstats.env; then
                    set -a
                    . ./dartboard/qase-runstats.env
                    set +a
                  else
                    echo 'Skipping Qase run stats artifact: required values are invalid.'
                  fi
                else
                  echo 'Skipping Qase run stats artifact: artifact was not found.'
                fi
                bash CI/slack-notification.sh "${currentBuild.currentResult}"
              """,
              returnStatus: true
            )
            if (notificationStatus != 0) {
              echo "Slack notification failed with exit code ${notificationStatus}."
            }
          }
        } else {
          echo 'SLACK_NOTIFICATION was not enabled'
        }
      }
    }
  }
}
