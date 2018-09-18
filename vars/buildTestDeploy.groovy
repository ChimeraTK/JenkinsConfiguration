def call() {
  pipeline {
    agent none
    stages {
      stage('build') {
        parallel {
          stage('build Ubuntu 16.04') {
            agent { label "Ubuntu1604" }
            steps {
              doBuild()
            }
          }
          stage('build Ubuntu 18.04') {
            agent { label "Ubuntu1804" }
            steps {
              doBuild()
            }
          }
        }
      }
    }
  }
}

def doBuild() {
  echo "HERE doBuild()"
  sh '''
    mkdir build
    cd build
    cmake ..
    make
  '''
}
