def call(String label) {
  pipeline {
    agent { label ${label} }
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
