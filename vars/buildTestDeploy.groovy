def call() {
  pipeline {
    agent none
    stages {
      stage('build') {
        parallel {
          stage('build Ubuntu 16.04 Release') {
            agent { label "Ubuntu1604" }
            steps {
              doAllRelease("Ubuntu1604")
            }
          }
          stage('build Ubuntu 16.04 Debug') {
            agent { label "Ubuntu1604" }
            steps {
              doAllDebug("Ubuntu1604")
            }
          }
          stage('build Ubuntu 18.04 Release') {
            agent { label "Ubuntu1804" }
            steps {
              doAllRelease("Ubuntu1804")
            }
          }
          stage('build Ubuntu 18.04 Debug') {
            agent { label "Ubuntu1804" }
            steps {
              doAllDebug("Ubuntu1804")
            }
          }
          stage('build SUSE Tumbeweed Release') {
            agent { label "SUSEtumbleweed" }
            steps {
              doAllRelease("SUSEtumbleweed")
            }
          }
          stage('build SUSE Tumbeweed Debug') {
            agent { label "SUSEtumbleweed" }
            steps {
              doAllDebug("SUSEtumbleweed")
            }
          }
        }
      }
    }
  }
}

def doAllRelease(String label) {
  doBuild(label,"Release")
  doStaticAnalysis(label,"Release")
  doTest(label,"Release")
  doInstall(label,"Release")
}

def doAllDebug(String label) {
  doBuild(label,"Debug")
  doStaticAnalysis(label,"Debug")
  doTest(label,"Debug")
  doCoverage(label,"Debug")
  doInstall(label,"Debug")
}

def doBuild(String label, String buildType) {
  sh """
    rm -rf ${buildType}
    mkdir -p ${buildType}/build
    mkdir -p ${buildType}/install-${label}-${buildType}
    cd ${buildType}/build
    cmake ../.. -DCMAKE_INSTALL_PREFIX=../install-${label}-${buildType} -DCMAKE_BUILD_TYPE=${buildType}
    make $MAKEOPTS
  """
}

def doStaticAnalysis(String label, String buildType) {
  sh """
    cd ${buildType}/build
    cppcheck --enable=all --xml --xml-version=2 2> ./cppcheck.xml .
  """
}

def doTest(String label, String buildType) {
  sh """
    cd ${buildType}/build
    ctest --no-compress-output -T Test
  """
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: "${buildType}/build/Testing/*/*.xml") ])
}

def doCoverage(String label, String buildType) {
  sh """
    cd ${buildType}/build
    make coverage
    /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info
  """
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "${buildType}/build/coverage.xml", conditionalCoverageTargets: '70, 0, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  publishHTML (target: [
      allowMissing: false,
      alwaysLinkToLastBuild: false,
      keepAll: false,
      reportDir: "${buildType}/build/coverage_html",
      reportFiles: 'index.html',
      reportName: "LCOV coverage report for ${label} ${buildType}"
  ])  
}

def doInstall(String label, String buildType) {
  sh """
    cd ${buildType}/build
    make install
  """
  archiveArtifacts artifacts: "${buildType}/install-${label}-${buildType}/**/*", onlyIfSuccessful: true
}
