def call(ArrayList<String> dependencyList) {
  pipeline {
    agent none
    stages {
      stage('build') {
        parallel {
          stage('build Ubuntu 16.04 Release') {
            agent { label "Ubuntu1604" }
            steps {
              doAllRelease(dependencyList, "Ubuntu1604")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 16.04 Debug') {
            agent { label "Ubuntu1604" }
            steps {
              doAllDebug(dependencyList, "Ubuntu1604")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 18.04 Release') {
            agent { label "Ubuntu1804" }
            steps {
              doAllRelease(dependencyList, "Ubuntu1804")
            }
            post { always { cleanUp() } }
          }
          stage('build Ubuntu 18.04 Debug') {
            agent { label "Ubuntu1804" }
            steps {
              doAllDebug(dependencyList, "Ubuntu1804")
            }
            post { always { cleanUp() } }
          }
          stage('build SUSE Tumbeweed Release') {
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
          }
        }
      }
    }
  }
}

def doAllRelease(ArrayList<String> dependencyList, String label) {
  doBuild(dependencyList, label, "Release")
  doStaticAnalysis(label,"Release")
  doTest(label,"Release")
  doInstall(dependencyList, label, "Release")
}

def doAllDebug(ArrayList<String> dependencyList, String label) {
  doBuild(label,"Debug")
  doStaticAnalysis(label,"Debug")
  doTest(label,"Debug")
  doCoverage(label,"Debug")
  doInstall(label,"Debug")
}

def doBuild(ArrayList<String> dependencyList, String label, String buildType) {
  script {
    dependencyList.each {
      copyArtifacts filter: "build/install-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
    }
  }
  sh """
    rm -rf --one-file-system build/root
    mkdir -p build/root
    cd build/root
    mkdir dev bin lib lib64 usr etc source build install
    if [ -e ../../artefacts/build/install-${label}-${buildType}.tgz ] ; then
      tar zxf ../../artefacts/build/install-${label}-${buildType}.tgz
    fi
    rsync -avx ../../ --exclude=build source/
    for d in dev bin lib lib64 usr etc ; do
      bindfs -n /\$d \$d
    done
    fakechroot chroot . /bin/bash <<....ENDCHROOT
      cd /build
      cmake ../source -DCMAKE_INSTALL_PREFIX=/install -DCMAKE_BUILD_TYPE=${buildType} -DCMAKE_MODULE_PATH=/install/share/cmake-${CMAKE_VERSION}/Modules
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

def cleanUp() {
  sh """
    cd build/root
    for d in dev bin lib lib64 usr etc ; do
      fusermount -u \$d
    done
  """
}
