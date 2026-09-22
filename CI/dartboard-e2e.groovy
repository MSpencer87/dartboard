#!groovy
// Fixed flow: deploy -> load -> Qase-driven k6 suite -> optional destroy.
// Infrastructure commands are delegated to dartboard-choice and Qase execution
// is delegated to qase-k6-runner so their implementations remain the source of truth.
@Library('qa-jenkins-library') _

def deploymentId
def deploymentCreated = false
def qaseK6Build

def downstreamResult(buildResult, jobName) {
  if (buildResult?.result == 'SUCCESS') {
    return
  }
  if (buildResult?.result == 'UNSTABLE') {
    currentBuild.result = 'UNSTABLE'
    return
  }
  error("${jobName} finished with result ${buildResult?.result ?: 'UNKNOWN'}")
}

def choiceParameters(command, deploymentIdValue = deploymentId) {
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
    stage('Deploy') {
      steps {
        script {
          def deployBuild = build(
            job: 'dartboard-choice',
            parameters: choiceParameters('deploy', ''),
            propagate: false,
            wait: true
          )
          downstreamResult(deployBuild, 'Deploy')

          // copy the rendered dart artifact from deploy, extract deploymentId for Load & Destroy
          copyArtifacts(
            filter: 'dartboard/**/rendered-dart.yaml',
            flatten: true,
            projectName: 'dartboard-choice',
            selector: specific("${deployBuild.number}")
          )
          if (!fileExists('rendered-dart.yaml')) {
            error('Deploy artifact rendered-dart.yaml was not found.')
          }
          def renderedDart = readYaml file: 'rendered-dart.yaml'
          deploymentId = renderedDart?.tofu_variables?.project_name?.toString()
          if (!deploymentId || deploymentId.startsWith('$')) {
            error("Deploy artifact did not contain a resolved project_name: ${deploymentId}")
          }
          currentBuild.description = "Deployment ${deploymentId}"
          echo "Using deployment ID from Deploy artifact: ${deploymentId}"
          deploymentCreated = true
        }
      }
    }

    stage('Load') {
      steps {
        script {
          downstreamResult(build(
            job: 'dartboard-choice',
            parameters: choiceParameters('load'), //deploymentId passed implicitly
            propagate: false,
            wait: true
          ), 'Load')
        }
      }
    }

    stage('Run Qase Test Suite') {
      steps {
        script {
          qaseK6Build = build(
            job: 'qase-k6-runner',
            parameters: [
              string(name: 'REPO', value: params.REPO ?: ''),
              string(name: 'BRANCH', value: params.BRANCH ?: ''),
              string(name: 'DEPLOYMENT_ID', value: deploymentId),
              string(name: 'S3_BUCKET_NAME', value: params.S3_BUCKET_NAME ?: ''),
              string(name: 'S3_BUCKET_REGION', value: params.S3_BUCKET_REGION ?: ''),
              string(name: 'QASE_TESTOPS_PROJECT', value: params.QASE_TESTOPS_PROJECT ?: ''),
              string(name: 'QASE_TESTOPS_RUN_ID', value: params.QASE_TESTOPS_RUN_ID ?: ''),
              string(name: 'K6_ENV', value: params.K6_ENV ?: '')
            ],
            propagate: false,
            wait: true
          )
          downstreamResult(qaseK6Build, 'Run Qase Test Suite')
        }
      }
    }
  }

  post {
    always {
      script {
        if (!deploymentCreated) {
          echo 'Deployment was not created; skipping cleanup.'
        } else if (!params.DESTROY) {
          echo "DESTROY is disabled; leaving deployment '${deploymentId}' running."
        } else {
          echo "Destroying deployment '${deploymentId}'..."
          def destroyBuild = build(
            job: 'dartboard-choice',
            parameters: choiceParameters('destroy'),
            propagate: false,
            wait: true
          )
          if (destroyBuild?.result != 'SUCCESS') {
            currentBuild.result = 'UNSTABLE'
            echo "Destroy finished with result ${destroyBuild?.result ?: 'UNKNOWN'}."
          }
        }

        if (params.SLACK_NOTIFICATION) {
          sh "rm -f qase-runstats.env"
          if (qaseK6Build?.number) {
            try {
              copyArtifacts(
                filter: 'dartboard/qase-runstats.env',
                flatten: true,
                projectName: 'qase-k6-runner',
                selector: specific("${qaseK6Build.number}")
              )
            } catch (e) {
              echo "Qase run stats artifact was unavailable: ${e.message}"
            }
          } else {
            echo 'Qase run stats artifact was unavailable: qase-k6-runner did not start.'
          }

          property.useWithCredentials(['DARTBOARD_SLACK_BOT_TOKEN', 'DARTBOARD_SLACK_CHANNEL']) {
            def notificationStatus = sh(
              script: """
                if [ -f qase-runstats.env ]; then
                  if [ "\$(wc -l < qase-runstats.env)" -eq 6 ] && \\
                    grep -Eq '^QASE_RUN_TOTAL=[0-9]+\$' qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_PASSED=[0-9]+\$' qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_FAILED=[0-9]+\$' qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_EXCEEDED_THRESHOLDS=[0-9]+\$' qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_PASS_PERCENT=[0-9]+\$' qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_URL=https://app\\.qase\\.io/run/[^[:space:]/]+/dartboard/[0-9]+\$' qase-runstats.env; then
                    set -a
                    . ./qase-runstats.env
                    set +a
                  else
                    echo 'Skipping Qase run stats artifact: required values are invalid.'
                  fi
                else
                  echo 'Skipping Qase run stats artifact: artifact was not found.'
                fi
                bash CI/slack-notification.sh ${currentBuild.currentResult}
              """,
              returnStatus: true
            )
            if (notificationStatus != 0) {
              echo "Slack notification failed with exit code ${notificationStatus}."
            }
          }
        } else {
          echo 'SLACK_NOTIFICATION is disabled; skipping Slack notification.'
        }
      }
    }
  }
}
