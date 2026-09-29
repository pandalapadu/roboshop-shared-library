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

            timeout(
                time: 15,
                unit: 'MINUTES'
            )
        }

        stages {

            /*
             * =========================================================
             * 1. READ VERSION
             * =========================================================
             */

            stage('Read Version') {

                steps {

                    script {

                        try {

                            def packageJson =
                                readJSON file: 'package.json'

                            env.APP_NAME =
                                packageJson.name

                            env.APP_VERSION =
                                packageJson.version

                            echo "======================================"
                            echo "Application : ${env.APP_NAME}"
                            echo "Version     : ${env.APP_VERSION}"
                            echo "Project     : ${env.PROJECT}"
                            echo "Component   : ${env.COMPONENT}"
                            echo "Region      : ${env.REGION}"
                            echo "======================================"

                        }
                        catch (Exception e) {

                            echo "❌ Read Version failed"
                            echo e.getMessage()

                            throw e
                        }
                    }
                }
            }


            /*
             * =========================================================
             * 2. INSTALL DEPENDENCIES
             * =========================================================
             */

            stage('Install Dependencies') {

                steps {

                    script {

                        try {

                            echo "Installing application dependencies..."

                            sh 'npm install'

                            echo "✅ Dependencies installed"

                        }
                        catch (Exception e) {

                            echo "❌ Dependency installation failed"
                            echo e.getMessage()

                            throw e
                        }
                    }
                }
            }


            /*
             * =========================================================
             * 3. UNIT TEST
             * =========================================================
             */

            stage('Unit Test') {

                steps {

                    script {

                        try {

                            echo "Running unit tests..."

                            sh 'CI=true npm test'

                            echo "✅ Unit tests successful"


                            /*
                             * GitHub:
                             *
                             * unit-tests
                             */

                            utils.safeUpdateCommitStatus(
                                "success",
                                "unit tests are successful",
                                "unit-tests"
                            )

                        }
                        catch (Exception e) {

                            echo "❌ Unit tests failed"

                            utils.safeUpdateCommitStatus(
                                "failure",
                                "unit tests are failed",
                                "unit-tests"
                            )

                            /*
                             * Preserve the original
                             * unit-test failure.
                             */

                            throw e
                        }
                    }
                }
            }


            /*
             * =========================================================
             * 4. LIBRARY SCAN
             * =========================================================
             */

            stage('Library Scan') {

                steps {

                    script {

                        try {

                            echo "Running dependency/library security scan..."

                            sh '''
                                npm audit \
                                    --audit-level=high
                            '''

                            echo "✅ Library scan successful"


                            /*
                             * GitHub:
                             *
                             * library-scan
                             */

                            utils.safeUpdateCommitStatus(
                                "success",
                                "library scan success",
                                "library-scan"
                            )

                        }
                        catch (Exception e) {

                            echo "❌ Library scan failed"

                            utils.safeUpdateCommitStatus(
                                "failure",
                                "library scan failed",
                                "library-scan"
                            )

                            throw e
                        }
                    }
                }
            }


            /*
             * =========================================================
             * 5. SONAR SCAN
             * =========================================================
             */

            stage('Sonar Scan') {

                steps {

                    script {

                        try {

                            echo "Starting SonarQube scan..."

                            withSonarQubeEnv('sonarqube') {

                                sh """
                                    sonar-scanner \
                                        -Dsonar.projectKey=${env.COMPONENT} \
                                        -Dsonar.projectName=${env.COMPONENT}
                                """
                            }

                            echo "✅ Sonar scan successful"


                            /*
                             * GitHub:
                             *
                             * sonar-scan
                             */

                            utils.safeUpdateCommitStatus(
                                "success",
                                "sonar scan are successful",
                                "sonar-scan"
                            )

                        }
                        catch (Exception e) {

                            echo "❌ Sonar scan failed"

                            utils.safeUpdateCommitStatus(
                                "failure",
                                "sonar scan failed",
                                "sonar-scan"
                            )

                            throw e
                        }
                    }
                }
            }


            /*
             * =========================================================
             * 6. DOCKER BUILD
             * =========================================================
             */

            stage('Docker Build') {

                steps {

                    script {

                        try {

                            echo "Building Docker image..."

                            sh """
                                docker build \
                                    -t ${env.APP_NAME}:${env.APP_VERSION} \
                                    .
                            """

                            echo "✅ Docker image build successful"


                            /*
                             * GitHub:
                             *
                             * build-image
                             */

                            utils.safeUpdateCommitStatus(
                                "success",
                                "image build success",
                                "build-image"
                            )

                        }
                        catch (Exception e) {

                            echo "❌ Docker image build failed"

                            utils.safeUpdateCommitStatus(
                                "failure",
                                "image build failed",
                                "build-image"
                            )

                            throw e
                        }
                    }
                }
            }


            /*
             * =========================================================
             * 7. TRIVY SCAN
             * =========================================================
             */

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

                                echo "⚠️ Trivy detected HIGH/CRITICAL vulnerabilities"

                                utils.safeUpdateCommitStatus(
                                    "failure",
                                    "trivy scan failed",
                                    "trivy-scan"
                                )

                                currentBuild.result =
                                    'UNSTABLE'

                            }
                            else {

                                echo "✅ Trivy scan successful"

                                utils.safeUpdateCommitStatus(
                                    "success",
                                    "trivy scan success",
                                    "trivy-scan"
                                )
                            }

                        }
                        catch (Exception e) {

                            echo "❌ Trivy execution failed"

                            utils.safeUpdateCommitStatus(
                                "failure",
                                "trivy scan failed",
                                "trivy-scan"
                            )

                            throw e
                        }
                    }
                }
            }


            /*
             * =========================================================
             * 8. ECR IMAGE PUSH
             * =========================================================
             */

            stage('ECR Image Push') {

                steps {

                    script {

                        try {

                            withAWS(
                                credentials: 'aws-credentials',
                                region: "${env.REGION}"
                            ) {

                                def ecrRegistry =
                                    "${env.ACC_ID}.dkr.ecr.${env.REGION}.amazonaws.com"

                                def ecrRepository =
                                    "${ecrRegistry}/${env.PROJECT}/${env.COMPONENT}"


                                echo "======================================"
                                echo "ECR Registry  : ${ecrRegistry}"
                                echo "ECR Repository: ${ecrRepository}"
                                echo "Image         : ${env.APP_VERSION}"
                                echo "======================================"


                                /*
                                 * ECR LOGIN
                                 */

                                sh """
                                    set -e

                                    aws ecr get-login-password \
                                        --region ${env.REGION} |
                                    docker login \
                                        --username AWS \
                                        --password-stdin ${ecrRegistry}
                                """


                                /*
                                 * DOCKER TAG
                                 */

                                sh """
                                    docker tag \
                                        ${env.APP_NAME}:${env.APP_VERSION} \
                                        ${ecrRepository}:${env.APP_VERSION}
                                """


                                /*
                                 * DOCKER PUSH
                                 */

                                sh """
                                    docker push \
                                        ${ecrRepository}:${env.APP_VERSION}
                                """


                                echo "✅ Image pushed successfully"


                                /*
                                 * GitHub:
                                 *
                                 * push-image
                                 */

                                utils.safeUpdateCommitStatus(
                                    "success",
                                    "image push success",
                                    "push-image"
                                )
                            }

                        }
                        catch (Exception e) {

                            echo "❌ ECR image push failed"

                            utils.safeUpdateCommitStatus(
                                "failure",
                                "image push failed",
                                "push-image"
                            )

                            throw e
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

                script {

                    try {

                        echo "Cleaning unused Docker images..."

                        sh 'docker image prune -f'

                        echo "✅ Docker cleanup completed"

                    }
                    catch (Exception e) {

                        echo "⚠️ Docker cleanup failed"

                        /*
                         * Do not change the actual pipeline
                         * result because cleanup failed.
                         */

                        echo e.getMessage()
                    }
                }
            }


            success {

                echo "======================================"
                echo "✅ ROBOSHOP PIPELINE SUCCESS"
                echo "======================================"
            }


            unstable {

                echo "======================================"
                echo "⚠️ ROBOSHOP PIPELINE UNSTABLE"
                echo "======================================"
            }


            failure {

                echo "======================================"
                echo "❌ ROBOSHOP PIPELINE FAILED"
                echo "======================================"
            }
        }
    }
}