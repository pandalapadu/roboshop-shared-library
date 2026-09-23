// jenkins-shared-library/
// ├── vars/
// │   ├── nodejsPipeline.groovy     # Encapsulates the entire end-to-end pipeline
// │   ├── trivyScan.groovy          # Modular reusable step
// │   └── ecrPush.groovy            # Modular reusable step
// ├── src/
// │   └── com/roboshop/Utils.groovy # Standard Groovy classes (optional helper methods)
// └── resources/                    # Configuration templates, JSON schemas, etc.

// this is function , by default if some one called this function , call function will be executed
def call (Map configMap){
pipeline {
    agent any
    environment {
        project = configMap.get("project")
        component = configMap.get("component")
    }
    stages {
        stage('Build') {
            steps {
                script {
                    sh """
                        echo "Building stage"
                        echo "Project is: ${project}, componenet is: ${component}"
                        printenv | sort
                    """
                }
            }
        }
        stage('Test') {
            steps {
                echo "Running unit and integration tests..." 
            }
        }
        stage('Deploy') {
            steps {
                echo "Deploying to environment..." 
            }
        }
    }
    post {
        always {
            cleanWs()
        }
        success {
            echo "Pipeline succeeded! Artifacts ready."
        }
        failure {
            echo "Build failed. Dispatching notification to alerts channel..."
        }
    }
}
}