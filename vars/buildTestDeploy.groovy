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
            agent { label "SUSEtumbleweed" }
            steps {
              doAllRelease()
            }
          }
          stage('build SUSE Tumbeweed Debug') {
            agent { label "SUSEtumbleweed" }
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
  doStaticAnalysis("Release")
  doTest("Release")
  doInstall("Release")
}

def doAllDebug() {
  doBuild("Debug")
  doStaticAnalysis("Debug")
  doTest("Debug")
  doCoverage("Debug")
  doInstall("Debug")
}

def doBuild(String buildType) {
  sh """
    rm -rf ${buildType}
    mkdir -p ${buildType}/build
    mkdir -p ${buildType}/install
    cd ${buildType}/build
    cmake .. -DCMAKE_INSTALL_PREFIX=../install -DCMAKE_BUILD_TYPE=${buildType}
    make $MAKEOPTS
  """
}

def doStaticAnalysis(String buildType) {
  sh """
    cd ${buildType}/build
    cppcheck --enable=all --xml --xml-version=2 2> ./cppcheck.xml .
  """
}

def doTest(String buildType) {
  sh """
    cd ${buildType}/build
    ctest --no-compress-output -T Test
  """
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: '${buildType}/build/Testing/*/*.xml') ])
}

def doCoverage(String buildType) {
  sh """
    cd ${buildType}/build
    make coverage
    /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info
  """
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: '${buildType}/build/coverage.xml', conditionalCoverageTargets: '70, 0, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  publishHTML (target: [
      allowMissing: false,
      alwaysLinkToLastBuild: false,
      keepAll: false,
      reportDir: '${buildType}/build/coverage_html',
      reportFiles: 'index.html',
      reportName: "LCOV coverage report"
  ])  
}

def doInstall(String buildType) {
  sh """
    cd ${buildType}/build
    make install
  """
  archiveArtifacts artifacts: '${buildType}/install/*', onlyIfSuccessful: true
}
