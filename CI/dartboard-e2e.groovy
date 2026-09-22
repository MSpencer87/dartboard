#!groovy
// Fixed flow: deploy -> load -> Qase-driven k6 suite -> optional destroy.
// Infrastructure commands are delegated to dartboard-choice and Qase execution
// is delegated to qase-k6-runner so their implementations remain the source of truth.
@Library('qa-jenkins-library') _

def deploymentId
def deploymentCreated = false
def qaseK6Build

def downstreamResult(buildResult, jobName) {
  if (buildResult?.number) {
    echo "${jobName} build #${buildResult.number} finished with result: ${buildResult.result ?: 'UNKNOWN'}"
  }
  if (buildResult?.result == 'SUCCESS') {
    return
  }
  if (buildResult?.result == 'UNSTABLE') {
    currentBuild.result = 'UNSTABLE'
    echo "${jobName} finished with UNSTABLE status."
    return
  }
  error("${jobName} build #${buildResult?.number} failed with result: ${buildResult?.result ?: 'UNKNOWN'}")
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

          // dartboard-choice archives dartboard/rendered-dart.yaml
          sh 'rm -rf dartboard/rendered-dart.yaml'
          copyArtifacts(
            filter: 'dartboard/rendered-dart.yaml',
            projectName: 'dartboard-choice',
            selector: specific("${deployBuild.number}")
          )

          def artifactPath = 'dartboard/rendered-dart.yaml'
          if (!fileExists(artifactPath)) {
            error("Deploy artifact was not found at expected path: ${artifactPath}")
          }

          def renderedDart = readYaml file: artifactPath
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
          if (!deploymentId) {
            error('Cannot execute Load stage: deploymentId is empty.')
          }
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
          if (!deploymentId) {
            error('Cannot execute Qase Test Suite: deploymentId is empty.')
          }
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
          try {
            def destroyBuild = build(
              job: 'dartboard-choice',
              parameters: choiceParameters('destroy'),
              propagate: false,
              wait: true
            )
            if (destroyBuild?.number) {
              echo "Destroy build #${destroyBuild.number} finished with result: ${destroyBuild.result ?: 'UNKNOWN'}"
            }
            if (destroyBuild?.result != 'SUCCESS') {
              currentBuild.result = 'UNSTABLE'
              echo "Destroy finished with result: ${destroyBuild?.result ?: 'UNKNOWN'}"
            }
          } catch (Exception e) {
            currentBuild.result = 'UNSTABLE'
            echo "Failed to trigger or complete Destroy build: ${e.message}"
          }
        }

        if (params.SLACK_NOTIFICATION) {
          sh 'rm -rf dartboard/qase-runstats.env'
          if (qaseK6Build?.number) {
            try {
              copyArtifacts(
                filter: 'dartboard/qase-runstats.env',
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
                if [ -f dartboard/qase-runstats.env ]; then
                  if [ "\$(wc -l < dartboard/qase-runstats.env)" -eq 6 ] && \\
                    grep -Eq '^QASE_RUN_TOTAL=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_PASSED=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_FAILED=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_EXCEEDED_THRESHOLDS=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_PASS_PERCENT=[0-9]+\$' dartboard/qase-runstats.env && \\
                    grep -Eq '^QASE_RUN_URL=https://app\\.qase\\.io/run/[^[:space:]/]+/dartboard/[0-9]+\$' dartboard/qase-runstats.env; then
                    set -a
                    . ./dartboard/qase-runstats.env
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
    cleanup {
      cleanWs notFailFast: true
    }
  }
}
