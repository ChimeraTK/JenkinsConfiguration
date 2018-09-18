def call() {
  pipeline {
    agent none
    stages {
      stage('build') {
        parallel {
          stage('build Ubuntu 16.04') {
            agent { label "Ubuntu1604" }
            steps {
              doEverything()
            }
          }
          stage('build Ubuntu 18.04') {
            agent { label "Ubuntu1804" }
            steps {
              doEverything()
            }
          }
        }
      }
    }
  }
}

def doEverything() {
  doBuild()
  doStaticAnalysis()
  doTest()
}

def doBuild() {
  echo "HERE doBuild()"
  sh """
    rm -rf build
    mkdir build
    cd build
    cmake ..
    make $MAKEOPTS
  """
}

def doStaticAnalysis() {
  echo "HERE doStaticAnalysis()"
  sh """
    cd build
    cppcheck --enable=all --xml --xml-version=2 2> ./cppcheck.xml .
  """
}

def doTest() {
  echo "HERE doTest()"
  sh """
    cd build
    ctest --no-compress-output -T Test
  """
  junit 'build/Testing/**/Test.xml'
}
