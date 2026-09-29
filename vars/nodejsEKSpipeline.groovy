def call(Map configMap) {

    pipeline {

        agent {
            node {
                label 'ROBOSHOP'
            }
        }

        environment {
            ACC_ID    = "453388807064"
            PROJECT   = configMap.get("project")
            COMPONENT = configMap.get("component")
            REGION    = "us-east-1"
        }

        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }

        stages {

            stage('Read Version') {
                steps {
                    script {
                        try {

                            def packageJson = readJSON file: 'package.json'

                            env.APP_NAME    = packageJson.name
                            env.APP_VERSION = packageJson.version

                            echo "Application : ${env.APP_NAME}"
                            echo "Version     : ${env.APP_VERSION}"

                        } catch (Exception e) {

                            echo "❌ Read Version failed: ${e.message}"

                            throw e
                        }
                    }
                }
            }


            stage('Install Dependencies') {
                steps {
                    script {
                        try {

                            echo "Installing dependencies..."

                            sh 'npm install'

                            echo "✅ Dependencies installed"

                        } catch (Exception e) {

                            echo "❌ Dependency installation failed: ${e.message}"

                            throw e
                        }
                    }
                }
            }


            stage('Unit Test') {
                steps {
                    script {
                        try {

                            echo "Running unit tests..."

                            sh 'CI=true npm test'

                            echo "✅ Unit tests passed"

                            try {

                                utils.updateCommitStatus(
                                    "success",
                                    "unit tests are successful",
                                    "unit-tests"
                                )

                            } catch (Exception statusError) {

                                echo "⚠️ GitHub status update failed:"
                                echo statusError.message
                            }

                        } catch (Exception e) {

                            echo "❌ Unit tests failed:"
                            echo e.message

                            try {

                                utils.updateCommitStatus(
                                    "failure",
                                    "unit tests are failed",
                                    "unit-tests"
                                )

                            } catch (Exception statusError) {

                                echo "⚠️ Failed to update GitHub failure status:"
                                echo statusError.message
                            }

                            throw e
                        }
                    }
                }
            }


            stage('Check Dependabot Alerts') {
                steps {
                    script {
                        try {

                            withCredentials([
                                string(
                                    credentialsId: 'github-token',
                                    variable: 'GH_TOKEN'
                                )
                            ]) {

                                sh '''
                                    set -e

                                    REPO="pandalapadu/${COMPONENT}"

                                    API_URL="https://api.github.com/repos/${REPO}/dependabot/alerts?state=open"

                                    echo "Checking Dependabot alerts..."
                                    echo "Repository: ${REPO}"

                                    HTTP_STATUS=$(curl -sS -L \
                                        -o alerts.json \
                                        -w "%{http_code}" \
                                        -H "Accept: application/vnd.github+json" \
                                        -H "Authorization: Bearer ${GH_TOKEN}" \
                                        -H "X-GitHub-Api-Version: 2022-11-28" \
                                        "${API_URL}")

                                    echo "HTTP Status: ${HTTP_STATUS}"

                                    if [ "${HTTP_STATUS}" -ne 200 ]; then
                                        echo "❌ GitHub API request failed"
                                        cat alerts.json
                                        exit 1
                                    fi

                                    TOTAL_ALERTS=$(jq \
                                        'if type=="array" then length else 0 end' \
                                        alerts.json
                                    )

                                    echo "Total alerts: ${TOTAL_ALERTS}"

                                    if [ "${TOTAL_ALERTS}" -eq 0 ]; then
                                        echo "✅ No open Dependabot alerts"
                                        exit 0
                                    fi

                                    jq -r '
                                        .[] |
                                        [
                                            .number,
                                            .security_vulnerability.severity,
                                            .dependency.package.name,
                                            .security_advisory.ghsa_id
                                        ] | @tsv
                                    ' alerts.json

                                    HIGH_CRITICAL_COUNT=$(jq '
                                        [
                                            .[] |
                                            select(
                                                .security_vulnerability.severity == "high"
                                                or
                                                .security_vulnerability.severity == "critical"
                                            )
                                        ] | length
                                    ' alerts.json)

                                    echo "High/Critical: ${HIGH_CRITICAL_COUNT}"

                                    if [ "${HIGH_CRITICAL_COUNT}" -gt 0 ]; then
                                        echo "❌ High/Critical vulnerabilities found"
                                        exit 1
                                    fi

                                    echo "✅ Dependabot check passed"
                                '''
                            }

                        } catch (Exception e) {

                            echo "❌ Dependabot stage failed:"
                            echo e.message

                            throw e
                        }
                    }
                }
            }


            stage('Docker Build') {
                steps {
                    script {
                        try {

                            echo "Building Docker image..."

                            sh """
                                docker build \
                                -t ${APP_NAME}:${APP_VERSION} \
                                .
                            """

                            echo "✅ Docker build completed"

                        } catch (Exception e) {

                            echo "❌ Docker build failed:"
                            echo e.message

                            throw e
                        }
                    }
                }
            }


            stage('Trivy Scan') {
                steps {
                    script {
                        try {

                            echo "Running Trivy Dockerfile scan..."

                            sh """
                                trivy config \
                                --exit-code 0 \
                                --severity HIGH,CRITICAL \
                                --format table \
                                ./Dockerfile
                            """


                            echo "Running Trivy image scan..."

                            def scanResult = sh(
                                script: """
                                    trivy image \
                                    --scanners vuln \
                                    --vuln-type os \
                                    --exit-code 1 \
                                    --severity HIGH,CRITICAL \
                                    --ignore-unfixed \
                                    --format table \
                                    ${APP_NAME}:${APP_VERSION}
                                """,
                                returnStatus: true
                            )


                            if (scanResult != 0) {

                                echo "⚠️ HIGH/CRITICAL vulnerabilities detected"

                                currentBuild.result = 'UNSTABLE'

                            } else {

                                echo "✅ Trivy scan passed"
                            }

                        } catch (Exception e) {

                            echo "❌ Trivy stage failed:"
                            echo e.message

                            throw e
                        }
                    }
                }
            }


            stage('ECR Image Push') {
                steps {
                    script {
                        try {

                            withAWS(
                                credentials: 'aws-credentials',
                                region: "${REGION}"
                            ) {

                                def registry =
                                    "${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com"

                                def repository =
                                    "${registry}/${PROJECT}/${COMPONENT}"


                                echo "ECR Registry : ${registry}"
                                echo "ECR Repository: ${repository}"


                                sh """
                                    set -e

                                    aws ecr get-login-password \
                                    --region ${REGION} |
                                    docker login \
                                    --username AWS \
                                    --password-stdin ${registry}

                                    docker tag \
                                    ${APP_NAME}:${APP_VERSION} \
                                    ${repository}:${APP_VERSION}

                                    docker push \
                                    ${repository}:${APP_VERSION}
                                """

                                echo "✅ Image pushed successfully"
                            }

                        } catch (Exception e) {

                            echo "❌ ECR push failed:"
                            echo e.message

                            throw e
                        }
                    }
                }
            }
        }


        post {

            always {
                script {
                    try {

                        echo "Cleaning Docker images..."

                        sh 'docker image prune -f'

                    } catch (Exception e) {

                        echo "⚠️ Docker cleanup failed:"
                        echo e.message
                    }
                }
            }


            success {
                echo "======================================"
                echo "✅ PIPELINE SUCCESS"
                echo "======================================"
            }


            unstable {
                echo "======================================"
                echo "⚠️ PIPELINE UNSTABLE"
                echo "======================================"
            }


            failure {
                echo "======================================"
                echo "❌ PIPELINE FAILED"
                echo "======================================"
            }
        }
    }
}