def call(String param) {
    stage('build') {
      steps {
        echo "Hallo hier myTest param = ${param}"
      }
    }
}
