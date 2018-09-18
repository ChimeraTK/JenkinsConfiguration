def call(String TargetType) {
  pipeline {
    agent { label "${TargetType}" }
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
