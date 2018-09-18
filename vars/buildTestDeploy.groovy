def call() {
  pipeline {
    agent none
    stages {
      stage('build') {
        parallel {
          stage('build Ubuntu 16.04 Release') {
            agent { label "Ubuntu1604" }
            steps {
              doAllRelease()
            }
          }
          stage('build Ubuntu 16.04 Debug') {
            agent { label "Ubuntu1604" }
            steps {
              doAllDebug()
            }
          }
          stage('build Ubuntu 18.04 Release') {
            agent { label "Ubuntu1804" }
            steps {
              doAllRelease()
            }
          }
          stage('build Ubuntu 18.04 Debug') {
            agent { label "Ubuntu1804" }
            steps {
              doAllDebug()
            }
          }
          stage('build SUSE Tumbeweed Release') {
            agent { label "SUSEtumbeweed" }
            steps {
              doAllRelease()
            }
          }
          stage('build SUSE Tumbeweed Debug') {
            agent { label "SUSEtumbeweed" }
            steps {
              doAllDebug()
            }
          }
        }
      }
    }
  }
}

def doAllRelease() {
  doBuild("Release")
  doStaticAnalysis()
  doTest()
}

def doAllDebug() {
  doBuild("Debug")
  doStaticAnalysis()
  doTest()
  doCoverage()
}

def doBuild(string buildType) {
  echo "HERE doBuild()"
  sh """
    rm -rf build
    mkdir build
    cd build
    cmake .. -DCMAKE_BUILD_TYPE=${buildType}
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
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: 'build/Testing/*/*.xml') ])
}

def doCoverage() {
  echo "HERE doCoverage()"
  sh """
    cd build
    make coverage
    /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info
  """
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: 'build/coverage.xml', conditionalCoverageTargets: '70, 0, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  publishHTML (target: [
      allowMissing: false,
      alwaysLinkToLastBuild: false,
      keepAll: false,
      reportDir: 'build/coverage_html',
      reportFiles: 'index.html',
      reportName: "LCOV coverage report"
  ])  
}
