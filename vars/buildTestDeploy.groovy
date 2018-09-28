def call(ArrayList<String> dependencyList) {
  pipeline {
    agent none
    stages {
      stage('build') {
        parallel {
          stage('build Ubuntu 16.04 Release') {
            agent { docker { image "builder:xenial" } }
            steps {
              doAllRelease(dependencyList, "Ubuntu1604")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 16.04 Debug') {
            agent { docker { image "builder:xenial" } }
            steps {
              doAllDebug(dependencyList, "Ubuntu1604")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 18.04 Release') {
            agent { docker { image "builder:bionic" } }
            steps {
              doAllRelease(dependencyList, "Ubuntu1804")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 18.04 Debug') {
            agent { docker { image "builder:bionic" } }
            steps {
              doAllDebug(dependencyList, "Ubuntu1804")
            }
            post { always { cleanUp() } }
          }
/*          stage('build SUSE Tumbeweed Release') {
            agent { label "SUSEtumbleweed" }
            steps {
              doAllRelease(dependencyList, "SUSEtumbleweed")
            }
            post { always { cleanUp() } }
          }
          stage('build SUSE Tumbeweed Debug') {
            agent { label "SUSEtumbleweed" }
            steps {
              doAllDebug(dependencyList, "SUSEtumbleweed")
            }
            post { always { cleanUp() } }
          } */
        }
      }
    }
  }
}

def doAllRelease(ArrayList<String> dependencyList, String label) {
  echo("doAllRelease ${label}")
  doBuild(dependencyList, label, "Release")
  doStaticAnalysis(label,"Release")
  doTest(label,"Release")
  doInstall(label, "Release")
}

def doAllDebug(ArrayList<String> dependencyList, String label) {
  echo("doAllDebug ${label}")
  doBuild(dependencyList, label,"Debug")
  doStaticAnalysis(label,"Debug")
  doTest(label,"Debug")
  doCoverage(label,"Debug")
  doInstall(label,"Debug")
}

def doBuild(ArrayList<String> dependencyList, String label, String buildType) {
  script {
    echo("doBuild ${label}")
    dependencyList.each {
      copyArtifacts filter: "build/install-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
    }
  }
  sh """
    rm -rf --one-file-system build
    mkdir -p build/build
    mkdir -p build/install
    if [ -e artefacts/build/install-${label}-${buildType}.tgz ] ; then
      tar zxf artefacts/build/install-${label}-${buildType}.tgz -L /
    fi
    cd build/build
    cmake ../.. -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=${buildType}
    make $MAKEOPTS
  """
}

def doStaticAnalysis(String label, String buildType) {
  sh """
    cppcheck --enable=all --xml --xml-version=2  -ibuild . 2> ./build/cppcheck.xml
  """
}

def doTest(String label, String buildType) {
  sh """
    cd build/build
    ctest --no-compress-output -T Test
  """
  //xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
  //       tools: [ CTest(pattern: "build/build/Testing/*/*.xml") ])
}

def doCoverage(String label, String buildType) {
  sh """
    cd build/build
    make coverage
    /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info
  """
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "build/build/coverage.xml", conditionalCoverageTargets: '70, 0, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  publishHTML (target: [
      allowMissing: false,
      alwaysLinkToLastBuild: false,
      keepAll: false,
      reportDir: "build/build/coverage_html",
      reportFiles: 'index.html',
      reportName: "LCOV coverage report for ${label} ${buildType}"
  ])  
}

def doInstall(String label, String buildType) {
  sh """
    cd build/build
    make install DESTDIR=../install
    cd ../install
    tar zcf ../install-${label}-${buildType}.tgz .
  """
  archiveArtifacts artifacts: "build/install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: true
}

def cleanUp() {
}
