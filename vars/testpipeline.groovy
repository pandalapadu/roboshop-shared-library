// jenkins-shared-library/
// ├── vars/
// │   ├── nodejsPipeline.groovy     # Encapsulates the entire end-to-end pipeline
// │   ├── trivyScan.groovy          # Modular reusable step
// │   └── ecrPush.groovy            # Modular reusable step
// ├── src/
// │   └── com/roboshop/Utils.groovy # Standard Groovy classes (optional helper methods)
// └── resources/                    # Configuration templates, JSON schemas, etc.

def call (){
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                echo "Compiling application for build ..." 
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