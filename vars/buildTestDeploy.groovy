/**********************************************************************************************************************/

def call(ArrayList<String> dependencyList) {
  pipeline {
    agent none
    stages {
      stage('build') {
        parallel {
          stage('Ubuntu 16.04 Release') {
            agent {
              docker {
                image "builder:xenial"
                // we need root access inside the container and access to the dummy pcie devices of the host
                args "-u 0 --device=/dev/mtcadummys0 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6"
              }
            }
            steps {
              doAll(dependencyList, "Ubuntu1604", "Release")
            }
          }
          stage('Ubuntu 16.04 Debug') {
            agent {
              docker {
                image "builder:xenial"
                // we need root access inside the container and access to the dummy pcie devices of the host
                args "-u 0 --device=/dev/mtcadummys0 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6"
              }
            }
            steps {
              doAll(dependencyList, "Ubuntu1604", "Debug")
            }
          }
          stage('Ubuntu 18.04 Release') {
            agent {
              docker {
                image "builder:bionic"
                // we need root access inside the container and access to the dummy pcie devices of the host
                args "-u 0 --device=/dev/mtcadummys0 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6"
              }
            }
            steps {
              doAll(dependencyList, "Ubuntu1804", "Release")
            }
          }
          stage('Ubuntu 18.04 Debug') {
            agent {
              docker {
                image "builder:bionic"
                // we need root access inside the container and access to the dummy pcie devices of the host
                args "-u 0 --device=/dev/mtcadummys0 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6"
              }
            }
            steps {
              doAll(dependencyList, "Ubuntu1804", "Debug")
            }
          }
          stage('SUSE Tumbeweed Release') {
            agent {
              docker {
                image "builder:tumbleweed"
                // we need root access inside the container and access to the dummy pcie devices of the host
                args "-u 0 --device=/dev/mtcadummys0 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6"
              }
            }
            steps {
              doAll(dependencyList, "SUSEtumbleweed", "Release")
            }
          }
          stage('SUSE Tumbeweed Debug') {
            agent {
              docker {
                image "builder:tumbleweed"
                // we need root access inside the container and access to the dummy pcie devices of the host
                args "-u 0 --device=/dev/mtcadummys0 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6"
              }
            }
            steps {
              doAll(dependencyList, "SUSEtumbleweed", "Debug")
            }
          } 
        } // end parallel
      } // end stage build
      stage('staticAnalysis') {
        agent {
          // run on host directly
          label 'Docker'
        }
        steps {
          doStaticAnalysis()
        }
      } // end stage analysis
    } // end stages
  } // end pipeline
}

/**********************************************************************************************************************/

def doAll(ArrayList<String> dependencyList, String label, String buildType) {
  timeout(activity: true, time: 10) {
    doBuild(dependencyList, label, buildType)
    doTest(label, buildType)
    if(buildType == "Debug") {
      doCoverage(label, buildType)
      doValgrind(label, buildType)
    }
    doInstall(label, buildType)
    doStaticAnalysis(label, buildType)
  }
}

/**********************************************************************************************************************/

def doBuild(ArrayList<String> dependencyList, String label, String buildType) {
  script {
    dependencyList.each {
      copyArtifacts filter: "install-${it}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
    }
  }
  sh """
    rm -rf build
    sudo -u msk_jenkins mkdir -p build/build
    sudo -u msk_jenkins mkdir -p build/install
    if [ -d artefacts ]; then
      for a in artefacts/install-*-${label}-${buildType}.tgz ; do
        echo === \$a
        tar zxf \"\${a}\" -C /
      done
    fi
    cd build/build
    sudo -u msk_jenkins cmake ../.. -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=${buildType}
    sudo -u msk_jenkins make $MAKEOPTS
  """
}

/**********************************************************************************************************************/

def doTest(String label, String buildType) {
  sh """
    cd build/build
    sudo -u msk_jenkins ctest --no-compress-output -T Test
    sudo -u msk_jenkins sed -i Testing/*/Test.xml -e 's_\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$_\\1${label}.${buildType}.\\2\\3_'
  """
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: "build/build/Testing/*/*.xml") ])
}

/**********************************************************************************************************************/

def doCoverage(String label, String buildType) {
  sh """
    cd build/build
    sudo -u msk_jenkins make coverage
    sudo -u msk_jenkins /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info
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

/**********************************************************************************************************************/

def doValgrind(String label, String buildType) {
  sh """
    cd build/build
    TESTS=`ctest -N | grep "Test *\\#" | sed -e 's/^ *Test *\\#.*: //'`
    for test in \$TEST; do
      sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --child-silent-after-fork=yes --tool=memcheck --leak-check=full --xml=yes --xml-file=valgrind.\${test}.memcheck.valgrind ctest -R \${test}
      sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --child-silent-after-fork=yes --tool=helgrind --xml=yes --xml-file=valgrind.\${test}.helgrind.valgrind ctest -R \${test}
    done
  """
  publishValgrind (
    failBuildOnInvalidReports: false,
    failBuildOnMissingReports: false,
    failThresholdDefinitelyLost: '',
    failThresholdInvalidReadWrite: '',
    failThresholdTotal: '',
    pattern: '*.valgrind',
    publishResultsForAbortedBuilds: false,
    publishResultsForFailedBuilds: false,
    sourceSubstitutionPaths: '',
    unstableThresholdDefinitelyLost: '',
    unstableThresholdInvalidReadWrite: '',
    unstableThresholdTotal: ''
  )
}

/**********************************************************************************************************************/

def doInstall(String label, String buildType) {
  sh """
    cd build/build
    sudo -u msk_jenkins make install DESTDIR=../install
    cd ../install
    sudo -u msk_jenkins tar zcf ../../install-${JOB_NAME}-${label}-${buildType}.tgz .
  """
  archiveArtifacts artifacts: "install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
}

/**********************************************************************************************************************/

def doStaticAnalysis() {
  sh """
    sudo -u msk_jenkins cppcheck --enable=all --xml --xml-version=2  -ibuild . 2> ./build/cppcheck.xml
  """
  warnings canComputeNew: false, canResolveRelativePaths: false, categoriesPattern: '', consoleParsers: [[parserName: 'GNU Make + GNU C Compiler (gcc)']], defaultEncoding: '', excludePattern: '.*-Wstrict-aliasing.*', healthy: '', includePattern: '', messagesPattern: '', unHealthy: '', unstableTotalAll: '0'
}

/**********************************************************************************************************************/

