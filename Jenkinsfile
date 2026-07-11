pipeline {
    agent any

    triggers {
        pollSCM('')
    }

    tools {
        maven 'M3'
    }

    stages {
        stage('Backend Unit Tests') {
            steps {
                sh './mvnw clean test'
            }
        }

        stage('Frontend Unit Tests') {
            agent {
                docker {
                    image 'node:20-alpine'
                    reuseNode true
                }
            }
            steps {
                dir('frontend') {
                    sh 'npm install'
                    sh 'npm run test -- --watch=false'
                }
            }
        }

        stage('Build & Package') {
            steps {
                sh './mvnw package -DskipTests'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                // 'SonarCloud' or 'MySonarServer' must match the server name set in Jenkins System Config
                withSonarQubeEnv('MySonarServer') {
                    // The plugin automatically injects the token and server URL variables here!
                    sh 'mvn clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.projectKey=buy01 \
  -Dsonar.projectName=buy01 '
                }
            }
        }

        stage('Quality Gate Enforcer') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    script {
                        // This step listens precisely for the webhook call from your SonarQube container
                        def qg = waitForQualityGate()
                        if (qg.status != 'OK') {
                            error "Pipeline aborted due to quality gate failure: ${qg.status}"
                        }
                    }
                }
            }
        }

        stage('Deploy Stack') {
            steps {
                echo 'Deploying the Microservices Platform...'
                sh 'docker network inspect shared-net >/dev/null 2>&1 || docker network create shared-net'
                sh 'docker compose down'
                sh 'docker compose up --build -d'
            }
        }
    }
    // df
    post {
        always {
            echo 'Processing and archiving all test results...'

            step([$class: 'GitHubCommitStatusSetter',
                  contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: 'jenkins-ci'],
                  statusResultSource: [$class: 'DefaultStatusResultSource']])

            // This reads BOTH reports simultaneously
            junit testResults: '**/target/surefire-reports/*.xml, **/frontend/junit-frontend.xml',
                  allowEmptyResults: true
        }

        success {
            echo 'Deployment successful!'

            mail to: 'justyoupika@gmail.com',
                 subject: "Pipeline Success: Job '${env.JOB_NAME}' [Build #${env.BUILD_NUMBER}]",
                 body: "Great news! The pipeline completed successfully.\n\nView the execution details here: ${env.BUILD_URL}"
        }
        failure {
            echo 'Build failed! Executing authenticated automated rollback...'

            mail to: 'justyoupika@gmail.com',
                 subject: "🛑 PIPELINE CRASHED: Job '${env.JOB_NAME}' [Build #${env.BUILD_NUMBER}]",
                 body: "Attention! The pipeline has failed during execution.\n\nReview the console logs to debug the failure here: ${env.BUILD_URL}console"

            sh 'git revert HEAD --no-edit'

            withCredentials([usernamePassword(credentialsId: 'gitea pull credentials',
                                          usernameVariable: 'GIT_USER',
                                          passwordVariable: 'GIT_TOKEN')]) {
                sh '''

                # Configure temporary credentials for this specific push command
                git remote set-url origin "https://${GIT_USER}:${GIT_TOKEN}@learn.zone01oujda.ma/git/amazighi/mr-jenk.git"

                # Push the revert cleanly back up
                git push origin HEAD:main
            '''
                                          }
        }
    }
}
