def call(String param) {
  pipeline {
    agent { label 'Ubuntu1604' }
    stages {
      stage('build') {
        steps {
          doBuild()
        }
      }
    }
  }
}

def doBuild() {
  echo "HERE doBuild()"
}
