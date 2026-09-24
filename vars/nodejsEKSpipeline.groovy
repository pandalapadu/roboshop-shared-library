def call (Map configMap) {
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
                    def packageJson = readJSON file: 'package.json'
                    def appName    = packageJson.name
                    def appVersion = packageJson.version
                    echo "Application: ${env.APP_NAME}"
                    echo "Version: ${env.APP_VERSION}"
                    env.APP_NAME    = appName
                    env.APP_VERSION = appVersion
                    printenv | sort    
                }
            }
        }
        stage('Install Dependencies') {
            steps {
                sh """
                    npm install
                """
            }
        }
        stage('Unit test') {
            steps {
                sh """
                    npm test
                """
            }
        }
        stage('Check Dependabot Alerts') {
            steps {
                withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                    sh '''
                        set -e

                        REPO="pandalapadu/catalogue"

                        echo "Querying Dependabot alerts for ${REPO}..."

                        HTTP_STATUS=$(curl -s -L -o alerts.json -w "%{http_code}" \
                            -H "Accept: application/vnd.github+json" \
                            -H "Authorization: Bearer ${GH_TOKEN}" \
                            -H "X-GitHub-Api-Version: 2022-11-28" \
                            "https://api.github.com/repos/${REPO}/dependabot/alerts?state=open")

                        if [ "$HTTP_STATUS" -ne 200 ]; then
                            echo "❌ GitHub API returned HTTP $HTTP_STATUS:"
                            cat alerts.json
                            exit 1
                        fi

                        # Check if any alerts exist
                        TOTAL_ALERTS=$(jq 'if type=="array" then length else 0 end' alerts.json)

                        if [ "$TOTAL_ALERTS" -eq 0 ]; then
                            echo "✅ No open Dependabot alerts found."
                            exit 0
                        fi

                        echo "---- Open Dependabot Alerts ----"
                        # Clean tab-separated output using jq @tsv (no backslash escape issues)
                        jq -r '.[] | [.number, .security_vulnerability.severity, .dependency.package.name, .security_advisory.ghsa_id] | @tsv' alerts.json

                        # Count High and Critical alerts
                        HIGH_CRITICAL_COUNT=$(jq '[.[] | select(.security_vulnerability.severity == "high" or .security_vulnerability.severity == "critical")] | length' alerts.json)

                        echo "--------------------------------"
                        echo "High/Critical alert count: ${HIGH_CRITICAL_COUNT}"

                        if [ "$HIGH_CRITICAL_COUNT" -gt 0 ]; then
                            echo "❌ Found ${HIGH_CRITICAL_COUNT} High/Critical dependency alert(s). Failing build."
                            exit 1
                        else
                            echo "✅ No High or Critical dependency alerts found. Passing build."
                        fi
                    '''
                }
            }
        }
        stage('Docker Build') {
            steps {
                sh """
                docker build -t ${env.APP_NAME}:${env.APP_VERSION} .
                """
            }
        }
        stage('Trivy Scan') {
            steps {
                script {
                    echo "Running Trivy Dockerfile misconfiguration scan..."
                    sh "trivy config --exit-code 0 --severity HIGH,CRITICAL --format table ./Dockerfile"

                    echo "Running Trivy container image vulnerability scan..."
                    def imageScan = sh(
                        script: "trivy image --scanners vuln --vuln-type os --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed --format table catalogue:1.0.0",
                        returnStatus: true
                    )

                    // If exit code is 1, unpatched HIGH/CRITICAL vulnerabilities exist
                    if (imageScan != 0) {
                        echo "⚠️ Trivy detected HIGH/CRITICAL CVEs in base image. Marking build UNSTABLE."
                        currentBuild.result = 'UNSTABLE'
                    } else {
                        echo "✅ No unpatched HIGH/CRITICAL OS vulnerabilities found. Build remains STABLE."
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