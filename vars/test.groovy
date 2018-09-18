def call(String param) {
  stages {
    stage('build') {
      steps {
        echo "Hallo hier myTest param = ${param}"
      }
    }
  }
}
