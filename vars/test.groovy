def call(String param) {
pipeline {
  agent any
  stages {
    stage('build') {
      steps {
        echo "Hallo hier myTest param = ${param}"
      }
    }
  }
}
}
