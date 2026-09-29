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
             * =========================================================
             * READ APPLICATION VERSION
             * =========================================================
             */

            stage('Read Version') {
                steps {
                    script {

                        def packageJson = readJSON file: 'package.json'

                        env.APP_NAME    = packageJson.name
                        env.APP_VERSION = packageJson.version

                        echo "Application : ${env.APP_NAME}"
                        echo "Version     : ${env.APP_VERSION}"

                        /*
                         * Useful for debugging.
                         * Avoid printing environment variables containing
                         * credentials/secrets in production.
                         */
                        sh '''
                            echo "Application : $APP_NAME"
                            echo "Version     : $APP_VERSION"
                            echo "Project     : $project"
                            echo "Component   : $component"
                            echo "Region      : $region"
                        '''
                    }
                }
            }


            /*
             * =========================================================
             * INSTALL DEPENDENCIES
             * =========================================================
             */

            stage('Install Dependencies') {
                steps {

                    /*
                     * If package-lock.json exists, npm ci is preferred
                     * for CI because it gives reproducible installs.
                     */
                    sh 'npm install'
                }
            }


            /*
             * =========================================================
             * UNIT TEST
             * =========================================================
             */

            stage('Unit Test') {
                steps {

                    script {

                        try {

                            sh 'CI=true npm test'

                            echo "✅ Unit tests passed"

                            utils.updateCommitStatus(
                                "success",
                                "unit tests are successful",
                                "unit-tests"
                            )

                        }
                        catch (Exception e) {

                            echo "❌ Unit tests failed"

                            /*
                             * Try to update GitHub status.
                             * Do not hide the original test failure
                             * if GitHub status update itself fails.
                             */
                            try {

                                utils.updateCommitStatus(
                                    "failure",
                                    "unit tests are failed",
                                    "unit-tests"
                                )

                            }
                            catch (Exception statusError) {

                                echo "⚠️ Failed to update GitHub commit status"
                                echo "GitHub status error: ${statusError.getMessage()}"

                            }

                            throw e
                        }
                    }
                }
            }


            /*
             * =========================================================
             * DEPENDABOT SECURITY CHECK
             * =========================================================
             */

            stage('Check Dependabot Alerts') {

                steps {

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

                            echo "=============================================="
                            echo "Checking Dependabot alerts"
                            echo "Repository : ${REPO}"
                            echo "API        : ${API_URL}"
                            echo "=============================================="

                            HTTP_STATUS=$(curl -sS -L \
                                -o alerts.json \
                                -w "%{http_code}" \
                                -H "Accept: application/vnd.github+json" \
                                -H "Authorization: Bearer ${GH_TOKEN}" \
                                -H "X-GitHub-Api-Version: 2022-11-28" \
                                "${API_URL}")

                            echo "GitHub API HTTP Status: ${HTTP_STATUS}"

                            if [ "${HTTP_STATUS}" -ne 200 ]; then

                                echo "❌ GitHub API request failed"

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

                                echo ""
                                echo "❌ Found ${HIGH_CRITICAL_COUNT} High/Critical dependency alert(s)."
                                echo "❌ Failing build."

                                exit 1

                            else

                                echo ""
                                echo "✅ No High or Critical dependency alerts found."
                                echo "✅ Dependabot security check passed."

                            fi
                        '''
                    }
                }
            }


            /*
             * =========================================================
             * DOCKER BUILD
             * =========================================================
             */

            stage('Docker Build') {

                steps {

                    echo "Building Docker image:"
                    echo "${env.APP_NAME}:${env.APP_VERSION}"

                    sh """
                        docker build \
                            -t ${env.APP_NAME}:${env.APP_VERSION} \
                            .
                    """
                }
            }


            /*
             * =========================================================
             * TRIVY SECURITY SCAN
             * =========================================================
             */

            stage('Trivy Scan') {

                steps {

                    script {

                        /*
                         * Dockerfile misconfiguration scan
                         *
                         * exit-code 0 means findings do not fail
                         * the build at this point.
                         */
                        echo "Running Trivy Dockerfile misconfiguration scan..."

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
                        echo "Running Trivy container image vulnerability scan..."

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

                            echo "⚠️ Trivy detected HIGH/CRITICAL CVEs in base image."
                            echo "⚠️ Marking build UNSTABLE."

                            currentBuild.result = 'UNSTABLE'

                        }
                        else {

                            echo "✅ No unpatched HIGH/CRITICAL OS vulnerabilities found."

                        }
                    }
                }
            }


            /*
             * =========================================================
             * PUSH IMAGE TO ECR
             * =========================================================
             */

            stage('ECR Image Push') {

                steps {

                    script {

                        withAWS(
                            credentials: 'aws-credentials',
                            region: "${env.region}"
                        ) {

                            def ecrRegistry =
                                "${env.acc_id}.dkr.ecr.${env.region}.amazonaws.com"

                            def ecrRepo =
                                "${ecrRegistry}/${env.project}/${env.component}"

                            echo "ECR Registry : ${ecrRegistry}"
                            echo "ECR Repository: ${ecrRepo}"
                            echo "Image        : ${env.APP_VERSION}"


                            sh """
                                set -e

                                echo "Logging in to ECR..."

                                aws ecr get-login-password \
                                    --region ${env.region} |
                                docker login \
                                    --username AWS \
                                    --password-stdin ${ecrRegistry}


                                echo "Tagging Docker image..."

                                docker tag \
                                    ${env.APP_NAME}:${env.APP_VERSION} \
                                    ${ecrRepo}:${env.APP_VERSION}


                                echo "Pushing Docker image..."

                                docker push \
                                    ${ecrRepo}:${env.APP_VERSION}


                                echo "✅ Image pushed successfully."

                            """
                        }
                    }
                }
            }
        }


        /*
         * =============================================================
         * POST ACTIONS
         * =============================================================
         */

        post {

            always {

                echo "Running cleanup..."

                sh '''
                    docker image prune -f
                '''
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