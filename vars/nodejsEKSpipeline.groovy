def call(Map configMap = [:]) {

    pipeline {

        agent {
            node {
                label 'ROBOSHOP'
            }
        }

        environment {
            ACC_ID   = '453388807064'
            PROJECT  = configMap.get('project', 'roboshop')
            COMPONENT = configMap.get('component', '')
            REGION   = 'us-east-1'

            ECR_REGISTRY = "${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com"
            ECR_REPOSITORY = "${ECR_REGISTRY}/${PROJECT}/${COMPONENT}"
        }

        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }

        stages {

            /*
             * ---------------------------------------------------------
             * 1. READ APPLICATION VERSION
             * ---------------------------------------------------------
             */
            stage('Read Version') {
                steps {
                    script {

                        if (!fileExists('package.json')) {
                            error 'package.json not found'
                        }

                        def packageJson = readJSON file: 'package.json'

                        env.APP_NAME = packageJson.name
                        env.APP_VERSION = packageJson.version

                        echo """
                        ========================================
                        Application : ${env.APP_NAME}
                        Version     : ${env.APP_VERSION}
                        Project     : ${env.PROJECT}
                        Component   : ${env.COMPONENT}
                        Region      : ${env.REGION}
                        ========================================
                        """
                    }
                }
            }


            /*
             * ---------------------------------------------------------
             * 2. INSTALL DEPENDENCIES
             * ---------------------------------------------------------
             */
            stage('Install Dependencies') {
                steps {
                    sh '''
                        set -e

                        echo "Installing NodeJS dependencies..."

                        if [ -f package-lock.json ]; then
                            npm ci
                        else
                            npm install
                        fi
                    '''
                }
            }


            /*
             * ---------------------------------------------------------
             * 3. UNIT TEST
             * GitHub Check:
             * unit-tests
             * ---------------------------------------------------------
             */
            stage('Unit Test') {
                steps {
                    script {

                        try {

                            echo "Running unit tests..."

                            sh '''
                                set -e
                                CI=true npm test
                            '''

                            utils.safeUpdateCommitStatus(
                                'success',
                                'unit tests are successful',
                                'unit-tests'
                            )

                        }
                        catch (Exception e) {

                            utils.safeUpdateCommitStatus(
                                'failure',
                                'unit tests are failed',
                                'unit-tests'
                            )

                            throw e
                        }
                    }
                }
            }


            /*
             * ---------------------------------------------------------
             * 4. LIBRARY / DEPENDENCY SECURITY SCAN
             * GitHub Check:
             * library-scan
             *
             * IMPORTANT:
             * Vulnerabilities are reported to GitHub but do not stop
             * the remaining pipeline stages.
             * ---------------------------------------------------------
             */
            stage('Library Scan') {
                steps {
                    script {

                        echo "Running dependency/library security scan..."

                        int auditResult = sh(
                            script: '''
                                npm audit --audit-level=high
                            ''',
                            returnStatus: true
                        )

                        if (auditResult != 0) {

                            echo """
                            ==================================================
                            WARNING: HIGH/CRITICAL dependency vulnerabilities
                            were detected by npm audit.
                            Pipeline will continue.
                            Jenkins build will be marked UNSTABLE.
                            ==================================================
                            """

                            utils.safeUpdateCommitStatus(
                                'failure',
                                'library scan found vulnerabilities',
                                'library-scan'
                            )

                            currentBuild.result = 'UNSTABLE'

                        } else {

                            echo "Library scan successful"

                            utils.safeUpdateCommitStatus(
                                'success',
                                'library scan successful',
                                'library-scan'
                            )
                        }
                    }
                }
            }


            /*
             * ---------------------------------------------------------
             * 5. SONARQUBE
             * GitHub Check:
             * sonar-scan
             * ---------------------------------------------------------
             */
            stage('Sonar Scan') {
                steps {
                    script {

                        try {

                            echo "Running SonarQube scan..."

                            withSonarQubeEnv('sonarqube') {

                                sh '''
                                    set -e

                                    sonar-scanner \
                                      -Dsonar.projectKey=${PROJECT}-${COMPONENT} \
                                      -Dsonar.projectName=${PROJECT}-${COMPONENT} \
                                      -Dsonar.sources=.
                                '''
                            }

                            utils.safeUpdateCommitStatus(
                                'success',
                                'sonar scan successful',
                                'sonar-scan'
                            )

                        }
                        catch (Exception e) {

                            utils.safeUpdateCommitStatus(
                                'failure',
                                'sonar scan failed',
                                'sonar-scan'
                            )

                            throw e
                        }
                    }
                }
            }


            /*
             * ---------------------------------------------------------
             * 6. DOCKER BUILD
             * GitHub Check:
             * build-image
             * ---------------------------------------------------------
             */
            stage('Docker Build') {
                steps {
                    script {

                        try {

                            echo "Building Docker image..."

                            sh """
                                set -e

                                docker build \
                                  -t ${APP_NAME}:${APP_VERSION} .
                            """

                            utils.safeUpdateCommitStatus(
                                'success',
                                'docker image build successful',
                                'build-image'
                            )

                        }
                        catch (Exception e) {

                            utils.safeUpdateCommitStatus(
                                'failure',
                                'docker image build failed',
                                'build-image'
                            )

                            throw e
                        }
                    }
                }
            }


            /*
             * ---------------------------------------------------------
             * 7. TRIVY SCAN
             * GitHub Check:
             * trivy-scan
             *
             * Trivy findings mark the build UNSTABLE but do not
             * prevent ECR push.
             * ---------------------------------------------------------
             */
            stage('Trivy Scan') {
                steps {
                    script {

                        try {

                            echo "Scanning Docker image with Trivy..."

                            int trivyResult = sh(
                                script: """
                                    trivy image \
                                      --exit-code 1 \
                                      --severity HIGH,CRITICAL \
                                      --ignore-unfixed \
                                      ${APP_NAME}:${APP_VERSION}
                                """,
                                returnStatus: true
                            )

                            if (trivyResult != 0) {

                                echo """
                                ==================================================
                                WARNING: Trivy detected HIGH/CRITICAL
                                vulnerabilities.
                                Pipeline will continue.
                                Jenkins build will be marked UNSTABLE.
                                ==================================================
                                """

                                utils.safeUpdateCommitStatus(
                                    'failure',
                                    'trivy scan found vulnerabilities',
                                    'trivy-scan'
                                )

                                currentBuild.result = 'UNSTABLE'

                            } else {

                                echo "Trivy scan successful"

                                utils.safeUpdateCommitStatus(
                                    'success',
                                    'trivy scan successful',
                                    'trivy-scan'
                                )
                            }

                        }
                        catch (Exception e) {

                            utils.safeUpdateCommitStatus(
                                'failure',
                                'trivy scan execution failed',
                                'trivy-scan'
                            )

                            throw e
                        }
                    }
                }
            }


            /*
             * ---------------------------------------------------------
             * 8. ECR PUSH
             * GitHub Check:
             * push-image
             * ---------------------------------------------------------
             */
            stage('ECR Image Push') {
                steps {
                    script {

                        try {

                            echo "Logging into Amazon ECR..."

                            withAWS(
                                credentials: 'aws-credentials',
                                region: "${REGION}"
                            ) {

                                sh """
                                    set -e

                                    aws ecr get-login-password \
                                      --region ${REGION} \
                                      | docker login \
                                      --username AWS \
                                      --password-stdin ${ECR_REGISTRY}

                                    docker tag \
                                      ${APP_NAME}:${APP_VERSION} \
                                      ${ECR_REPOSITORY}:${APP_VERSION}

                                    docker push \
                                      ${ECR_REPOSITORY}:${APP_VERSION}
                                """
                            }

                            echo """
                            ========================================
                            Image pushed successfully

                            Image:
                            ${ECR_REPOSITORY}:${APP_VERSION}
                            ========================================
                            """

                            utils.safeUpdateCommitStatus(
                                'success',
                                'docker image pushed to ECR',
                                'push-image'
                            )

                        }
                        catch (Exception e) {

                            utils.safeUpdateCommitStatus(
                                'failure',
                                'docker image push failed',
                                'push-image'
                            )

                            throw e
                        }
                    }
                }
            }
        }


        /*
         * -------------------------------------------------------------
         * POST ACTIONS
         * -------------------------------------------------------------
         */
        post {

            always {

                echo "Cleaning unused Docker images..."

                sh '''
                    docker image prune -f || true
                '''
            }

            success {

                echo """
                ========================================
                Jenkins Pipeline SUCCESS
                Project   : ${PROJECT}
                Component : ${COMPONENT}
                Version   : ${APP_VERSION}
                ========================================
                """
            }

            unstable {

                echo """
                ========================================
                Jenkins Pipeline UNSTABLE

                Security findings were detected,
                but the pipeline completed.

                Project   : ${PROJECT}
                Component : ${COMPONENT}
                Version   : ${APP_VERSION}
                ========================================
                """
            }

            failure {

                echo """
                ========================================
                Jenkins Pipeline FAILED
                Project   : ${PROJECT}
                Component : ${COMPONENT}
                ========================================
                """
            }
        }
    }
}