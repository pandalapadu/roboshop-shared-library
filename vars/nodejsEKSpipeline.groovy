def call(Map configMap) {

    pipeline {

        agent {
            node {
                label 'ROBOSHOP'
            }
        }

        environment {
            acc_id    = "453388807064"
            project   = configMap.get("project")
            component = configMap.get("component")
            region    = "us-east-1"
        }

        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }

        stages {

            /*
             * ==========================================================
             * READ VERSION
             * ==========================================================
             */

            stage('Read Version') {

                steps {

                    script {

                        try {

                            echo "Starting Read Version stage..."

                            def packageJson = readJSON file: 'package.json'

                            env.APP_NAME    = packageJson.name
                            env.APP_VERSION = packageJson.version

                            echo "Application : ${env.APP_NAME}"
                            echo "Version     : ${env.APP_VERSION}"
                            echo "Project     : ${env.project}"
                            echo "Component   : ${env.component}"
                            echo "Region      : ${env.region}"

                            echo "Read Version stage completed successfully."

                        }
                        catch (Exception e) {

                            echo "❌ Read Version stage failed."
                            echo "Error: ${e.getMessage()}"

                            throw e
                        }
                    }
                }
            }


            /*
             * ==========================================================
             * INSTALL DEPENDENCIES
             * ==========================================================
             */

            stage('Install Dependencies') {

                steps {

                    script {

                        try {

                            echo "Starting dependency installation..."

                            sh 'npm install'

                            echo "✅ Dependencies installed successfully."

                        }
                        catch (Exception e) {

                            echo "❌ Install Dependencies stage failed."
                            echo "Error: ${e.getMessage()}"

                            throw e
                        }
                    }
                }
            }


            /*
             * ==========================================================
             * UNIT TEST
             * ==========================================================
             */

            stage('Unit Test') {

                steps {

                    script {

                        try {

                            echo "Starting unit tests..."

                            sh 'CI=true npm test'

                            echo "✅ Unit tests completed successfully."


                            /*
                             * Update GitHub status
                             *
                             * If this fails, we don't want the GitHub
                             * status failure to hide the actual test result.
                             */

                            try {

                                utils.updateCommitStatus(
                                    "success",
                                    "unit tests are successful",
                                    "unit-tests"
                                )

                                echo "✅ GitHub commit status updated successfully."

                            }
                            catch (Exception statusError) {

                                echo "⚠️ GitHub commit status update failed."
                                echo "Status Error: ${statusError.getMessage()}"

                                /*
                                 * We intentionally don't fail the build here.
                                 * Unit tests themselves passed.
                                 */
                            }

                        }
                        catch (Exception e) {

                            echo "❌ Unit tests failed."
                            echo "Error: ${e.getMessage()}"


                            /*
                             * Try to report FAILURE to GitHub.
                             *
                             * If GitHub update itself fails, don't hide
                             * the original unit-test failure.
                             */

                            try {

                                utils.updateCommitStatus(
                                    "failure",
                                    "unit tests are failed",
                                    "unit-tests"
                                )

                                echo "GitHub failure status updated."

                            }
                            catch (Exception statusError) {

                                echo "⚠️ Failed to update GitHub failure status."
                                echo "Status Error: ${statusError.getMessage()}"

                            }


                            /*
                             * Re-throw the ORIGINAL exception.
                             */

                            throw e
                        }
                    }
                }
            }


            /*
             * ==========================================================
             * DEPENDABOT SECURITY CHECK
             * ==========================================================
             */

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

                                    REPO="pandalapadu/${component}"

                                    API_URL="https://api.github.com/repos/${REPO}/dependabot/alerts?state=open"

                                    echo "=========================================="
                                    echo "Checking Dependabot Alerts"
                                    echo "Repository : ${REPO}"
                                    echo "API URL    : ${API_URL}"
                                    echo "=========================================="


                                    HTTP_STATUS=$(curl -sS -L \
                                        -o alerts.json \
                                        -w "%{http_code}" \
                                        -H "Accept: application/vnd.github+json" \
                                        -H "Authorization: Bearer ${GH_TOKEN}" \
                                        -H "X-GitHub-Api-Version: 2022-11-28" \
                                        "${API_URL}")


                                    echo "GitHub API HTTP Status: ${HTTP_STATUS}"


                                    if [ "${HTTP_STATUS}" -ne 200 ]; then

                                        echo "❌ GitHub API request failed."

                                        echo "Response:"
                                        cat alerts.json

                                        exit 1
                                    fi


                                    TOTAL_ALERTS=$(jq \
                                        'if type == "array" then length else 0 end' \
                                        alerts.json
                                    )


                                    echo "Total open Dependabot alerts: ${TOTAL_ALERTS}"


                                    if [ "${TOTAL_ALERTS}" -eq 0 ]; then

                                        echo "✅ No open Dependabot alerts found."

                                        exit 0
                                    fi


                                    echo ""
                                    echo "----------- Open Dependabot Alerts -----------"


                                    jq -r '
                                        .[] |
                                        [
                                            .number,
                                            .security_vulnerability.severity,
                                            .dependency.package.name,
                                            .security_advisory.ghsa_id
                                        ] |
                                        @tsv
                                    ' alerts.json


                                    echo "----------------------------------------------"


                                    HIGH_CRITICAL_COUNT=$(jq '
                                        [
                                            .[] |
                                            select(
                                                .security_vulnerability.severity == "high"
                                                or
                                                .security_vulnerability.severity == "critical"
                                            )
                                        ] |
                                        length
                                    ' alerts.json)


                                    echo "High/Critical alert count: ${HIGH_CRITICAL_COUNT}"


                                    if [ "${HIGH_CRITICAL_COUNT}" -gt 0 ]; then

                                        echo "❌ Found ${HIGH_CRITICAL_COUNT} High/Critical dependency alert(s)."

                                        exit 1

                                    else

                                        echo "✅ No High/Critical dependency alerts found."

                                    fi
                                '''
                            }

                            echo "✅ Dependabot security check completed successfully."

                        }
                        catch (Exception e) {

                            echo "❌ Dependabot stage failed."
                            echo "Error: ${e.getMessage()}"

                            throw e
                        }
                    }
                }
            }


            /*
             * ==========================================================
             * DOCKER BUILD
             * ==========================================================
             */

            stage('Docker Build') {

                steps {

                    script {

                        try {

                            echo "Starting Docker build..."

                            echo "Image:"
                            echo "${env.APP_NAME}:${env.APP_VERSION}"

                            sh """
                                docker build \
                                    -t ${env.APP_NAME}:${env.APP_VERSION} \
                                    .
                            """

                            echo "✅ Docker image built successfully."

                        }
                        catch (Exception e) {

                            echo "❌ Docker Build stage failed."
                            echo "Error: ${e.getMessage()}"

                            throw e
                        }
                    }
                }
            }


            /*
             * ==========================================================
             * TRIVY SCAN
             * ==========================================================
             */

            stage('Trivy Scan') {

                steps {

                    script {

                        try {

                            echo "Starting Trivy security scan..."


                            /*
                             * Dockerfile configuration scan
                             */

                            echo "Running Trivy Dockerfile scan..."

                            sh """
                                trivy config \
                                    --exit-code 0 \
                                    --severity HIGH,CRITICAL \
                                    --format table \
                                    ./Dockerfile
                            """


                            /*
                             * Container image vulnerability scan
                             */

                            echo "Running Trivy image vulnerability scan..."

                            def imageScan = sh(
                                script: """
                                    trivy image \
                                        --scanners vuln \
                                        --vuln-type os \
                                        --exit-code 1 \
                                        --severity HIGH,CRITICAL \
                                        --ignore-unfixed \
                                        --format table \
                                        ${env.APP_NAME}:${env.APP_VERSION}
                                """,
                                returnStatus: true
                            )


                            if (imageScan != 0) {

                                echo "⚠️ Trivy detected HIGH/CRITICAL CVEs."

                                currentBuild.result = 'UNSTABLE'

                                echo "Build marked as UNSTABLE."

                            }
                            else {

                                echo "✅ No unpatched HIGH/CRITICAL OS vulnerabilities found."

                            }

                        }
                        catch (Exception e) {

                            echo "❌ Trivy Scan stage failed."
                            echo "Error: ${e.getMessage()}"

                            throw e
                        }
                    }
                }
            }


            /*
             * ==========================================================
             * ECR IMAGE PUSH
             * ==========================================================
             */

            stage('ECR Image Push') {

                steps {

                    script {

                        try {

                            echo "Starting ECR image push..."


                            withAWS(
                                credentials: 'aws-credentials',
                                region: "${env.region}"
                            ) {

                                def ecrRegistry =
                                    "${env.acc_id}.dkr.ecr.${env.region}.amazonaws.com"

                                def ecrRepo =
                                    "${ecrRegistry}/${env.project}/${env.component}"


                                echo "ECR Registry  : ${ecrRegistry}"
                                echo "ECR Repository: ${ecrRepo}"
                                echo "Image         : ${env.APP_NAME}:${env.APP_VERSION}"


                                /*
                                 * Login to ECR
                                 */

                                echo "Logging into ECR..."

                                sh """
                                    aws ecr get-login-password \
                                        --region ${env.region} |
                                    docker login \
                                        --username AWS \
                                        --password-stdin ${ecrRegistry}
                                """


                                /*
                                 * Tag image
                                 */

                                echo "Tagging Docker image..."

                                sh """
                                    docker tag \
                                        ${env.APP_NAME}:${env.APP_VERSION} \
                                        ${ecrRepo}:${env.APP_VERSION}
                                """


                                /*
                                 * Push image
                                 */

                                echo "Pushing Docker image..."

                                sh """
                                    docker push \
                                        ${ecrRepo}:${env.APP_VERSION}
                                """


                                echo "✅ Docker image pushed successfully."
                            }

                        }
                        catch (Exception e) {

                            echo "❌ ECR Image Push stage failed."
                            echo "Error: ${e.getMessage()}"

                            throw e
                        }
                    }
                }
            }
        }


        /*
         * ==============================================================
         * POST ACTIONS
         * ==============================================================
         */

        post {

            always {

                script {

                    try {

                        echo "Running Docker cleanup..."

                        sh 'docker image prune -f'

                        echo "✅ Docker cleanup completed."

                    }
                    catch (Exception e) {

                        /*
                         * Cleanup failure should not hide the actual
                         * pipeline failure.
                         */

                        echo "⚠️ Docker cleanup failed."
                        echo "Cleanup Error: ${e.getMessage()}"

                    }
                }
            }


            success {

                echo "=========================================="
                echo "✅ ROBOSHOP PIPELINE SUCCESS"
                echo "=========================================="
            }


            unstable {

                echo "=========================================="
                echo "⚠️ ROBOSHOP PIPELINE UNSTABLE"
                echo "=========================================="
            }


            failure {

                echo "=========================================="
                echo "❌ ROBOSHOP PIPELINE FAILED"
                echo "=========================================="
            }
        }
    }
}