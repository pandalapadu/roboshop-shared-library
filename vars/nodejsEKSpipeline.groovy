def call () {
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
        stage('Read Version') {
            steps {
                script {
                    def packageJson = readJSON file: '8-Jenkins/8-01-catalogue/package.json'

                    def appName    = packageJson.name
                    def appVersion = packageJson.version

                    echo "Application: ${appName}"
                    echo "Version: ${appVersion}"

                    env.APP_NAME    = appName
                    env.APP_VERSION = appVersion
                }
            }
        }

        stage('Install Dependencies') {
            steps {
                dir('8-Jenkins/8-01-catalogue') {
                    sh 'npm install'
                }
            }
        }
        // this command gives coverage report and test case report , sonar access this report 
        stage('Unit Tests') {
            steps {
                dir('8-Jenkins/8-01-catalogue') {
                    sh 'npm test'
                }
            }
        }
        // stage('SonarQube Analysis') {
        //     steps {
        //         dir('8-Jenkins/8-01-catalogue') {
        //             // Name must match the SonarQube Server name in Manage Jenkins -> System
        //             withSonarQubeEnv('sonar-server') {
        //                 sh """
        //                     ${tool 'sonar-8'}/bin/sonar-scanner 
                        
        //                 """
        //             }
        //         }
        //     }
        // }
        // stage('Quality Gate') {
        //     steps {
        //         timeout(time: 10, unit: 'MINUTES') {
        //             script {
        //                 def qg = waitForQualityGate()
        //                 if (qg.status != 'OK') {
        //                     unstable(message: "Quality Gate failed with status: ${qg.status}")
        //                 } 
        //             }     
        //         }         
        //     }            
        // }
        // stage('Check Dependabot Alerts') {
        //     steps {
        //         withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
        //             sh '''
        //                 set -e

        //                 REPO="https://github.com/pandalapadu/my-AWS-DevOps-Project//8-Jenkins/8-01-catalogue"

        //                 curl -s -L \
        //                 -H "Accept: application/vnd.github+json" \
        //                 -H "Authorization: Bearer ${GH_TOKEN}" \
        //                 -H "X-GitHub-Api-Version: 2026-03-10" \
        //                 "https://api.github.com/repos/${REPO}/dependabot/alerts?state=open" \
        //                 -o alerts.json

        //                 echo "---- Open Dependabot Alerts ----"
        //                 jq -r '.[] | "\\(.number)\\t\\(.security_vulnerability.severity)\\t\\(.dependency.package.name)\\t\\(.security_advisory.ghsa_id)"' alerts.json

        //                 HIGH_CRITICAL_COUNT=$(jq '[.[] | select(.security_vulnerability.severity == "high" or .security_vulnerability.severity == "critical")] | length' alerts.json)

        //                 echo "High/Critical alert count: ${HIGH_CRITICAL_COUNT}"

        //                 if [ "$HIGH_CRITICAL_COUNT" -gt 0 ]; then
        //                     echo "❌ Found ${HIGH_CRITICAL_COUNT} High/Critical severity dependency alert(s). Failing build."
        //                     exit 1
        //                 else
        //                     echo "✅ No High/Critical dependency alerts found."
        //                 fi
        //             '''
        //         }
        //     }
        // }

        stage('Docker Build') {
            steps {
                dir('8-Jenkins/8-01-catalogue') {
                    sh "docker build -t ${env.APP_NAME}:${env.APP_VERSION} ."
                }
            }
        }
        stage('Trivy Scan') {
            steps {
                dir('8-Jenkins/8-01-catalogue') {
                    script {
                        echo "Running Trivy Dockerfile scan..."
                        sh "trivy config --exit-code 0 --severity HIGH,CRITICAL --format table ./Dockerfile"

                        echo "Running Trivy container image scan..."
                        def imageScan = sh(
                            script: "trivy image --scanners vuln --pkg-types os --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed --format table ${env.APP_NAME}:${env.APP_VERSION}",
                            returnStatus: true
                        )

                        // Warn if OS vulnerabilities exist, but allow the pipeline to proceed to ECR push
                        if (imageScan != 0) {
                            unstable(message: "Trivy discovered unpatched HIGH/CRITICAL CVEs in base image.")
                        }
                    }
                }
            }
        }

        stage('ECR Image push') {
            steps {
                script {
                    withAWS(credentials: 'aws-credentials', region: "${env.region}") {
                        def ecrRegistry = "${env.acc_id}.dkr.ecr.${env.region}.amazonaws.com"
                        def ecrRepo     = "${ecrRegistry}/${env.project}/${env.component}"

                        sh """
                            # 1. Login to Amazon ECR
                            aws ecr get-login-password --region ${env.region} | docker login --username AWS --password-stdin ${ecrRegistry}

                            # 2. Tag image with version and latest
                            docker tag ${env.APP_NAME}:${env.APP_VERSION} ${ecrRepo}:${env.APP_VERSION}
                           
                            
                            # 3. Push to ECR
                            docker push ${ecrRepo}:${env.APP_VERSION}
                            
                        """
                    }
                }
            }
        }
    }
       
    post {
        always {
            echo "it will run always"
            // Optional: prune untagged/dangling images to save disk space on the agent
            //  docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
            sh 'docker image prune -f'
        }
        success {
            echo "I will run only success"
        }
        failure {
            echo "I will run if build failed"
        }
    }
}
}