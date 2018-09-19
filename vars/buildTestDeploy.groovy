def call(ArrayList<String> dependencyList) {
  pipeline {
    agent none
    stages {
      stage('obtain artefacts') {
        parallel {
          stage('obtain artefacts for Ubuntu 16.04') {
            agent { label "Ubuntu1604" }
            steps {
              script {
                dependencyList.each {
                  copyArtifacts filter: 'build/install-Ubuntu1604-*.tgz', fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
                }
              }
            }
          }
          stage('obtain artefacts for Ubuntu 18.04') {
            agent { label "Ubuntu1804" }
            steps {
              script {
                dependencyList.each {
                  copyArtifacts filter: 'build/install-Ubuntu1804-*.tgz', fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
                }
              }
            }
          }
          stage('obtain artefacts for SUSE Tumbeweed') {
            agent { label "SUSEtumbleweed" }
            steps {
              script {
                dependencyList.each {
                  copyArtifacts filter: 'build/install-SUSEtumbleweed-*.tgz', fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
                }
              }
            }
          }
        }
      }
      stage('build') {
        parallel {
          stage('build Ubuntu 16.04 Release') {
            agent { label "Ubuntu1604" }
            steps {
              doAllRelease("Ubuntu1604")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 16.04 Debug') {
            agent { label "Ubuntu1604" }
            steps {
              doAllDebug("Ubuntu1604")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 18.04 Release') {
            agent { label "Ubuntu1804" }
            steps {
              doAllRelease("Ubuntu1804")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 18.04 Debug') {
            agent { label "Ubuntu1804" }
            steps {
              doAllDebug("Ubuntu1804")
            }
            post { always { cleanUp() } }
          }
          stage('build SUSE Tumbeweed Release') {
            agent { label "SUSEtumbleweed" }
            steps {
              doAllRelease("SUSEtumbleweed")
            }
            post { always { cleanUp() } }
          }
          stage('build SUSE Tumbeweed Debug') {
            agent { label "SUSEtumbleweed" }
            steps {
              doAllDebug("SUSEtumbleweed")
            }
            post { always { cleanUp() } }
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

def cleanUp() {
  sh """
    cd build/root
    for d in dev bin lib lib64 usr etc source ; do
      fusermount -u \$d
    done
  """
}

def doBuild(String label, String buildType) {
  sh """
    rm -rf --one-file-system build/root
    mkdir -p build/root
    cd build/root
    mkdir dev bin lib lib64 usr etc source build install
    for a in ../../artefacts/build/install-${label}-${buildType}*.tgz ; do
      tar zxf \$a
    done
    bindfs -n ../.. source
    for d in dev bin lib lib64 usr etc ; do
      bindfs -n /\$d \$d
    done
    fakechroot chroot . /bin/bash <<....ENDCHROOT
      cd /build
      cmake ../source -DCMAKE_INSTALL_PREFIX=../install -DCMAKE_BUILD_TYPE=${buildType} -DCMAKE_MODULES_PATH=/install/share/cmake-${CMAKE_VERSION}/Modules
      make $MAKEOPTS
....ENDCHROOT
  """
}

def doStaticAnalysis(String label, String buildType) {
  sh """
    cppcheck --enable=all --xml --xml-version=2  -ibuild . 2> ./build/cppcheck.xml
  """
}

def doTest(String label, String buildType) {
  sh """
    cd build/root
    fakechroot chroot . /bin/bash <<....ENDCHROOT
      cd /build
      ctest --no-compress-output -T Test
....ENDCHROOT
  """
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: "${buildType}/build/Testing/*/*.xml") ])
}

def doCoverage(String label, String buildType) {
  sh """
    cd build/root
    fakechroot chroot . /bin/bash <<....ENDCHROOT
      cd /build
      make coverage
....ENDCHROOT
    cd ..
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
    cd build/root
    fakechroot chroot . /bin/bash <<....ENDCHROOT
      cd /build
      make install
....ENDCHROOT
    tar zcf ../install-${label}-${buildType}.tgz install
  """
  archiveArtifacts artifacts: "build/install-${label}-${buildType}.tgz", onlyIfSuccessful: true
}
