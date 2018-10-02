/**********************************************************************************************************************/

def call(ArrayList<String> dependencyList) {
  pipeline {
    agent none
    stages {
      stage('build') {
        // Run the build stages for all labels + build types in parallel, each in a separate docker container
        // Note: If the list of labels + build types is extended here, don't forget to update also the doPublish() function!
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
      stage('publishResults') {
        agent {
          // run on host directly
          label 'Docker'
        }
        steps {
          doPublish()
        }
      } // end stage analysis
    } // end stages
  } // end pipeline
}

/**********************************************************************************************************************/

def doAll(ArrayList<String> dependencyList, String label, String buildType) {

  // Add inactivity timeout of 10 minutes (build will be interrupted if 10 minutes no log output has been produced)
  timeout(activity: true, time: 10) {

    doBuild(dependencyList, label, buildType)
    doTest(label, buildType)

    if(buildType == "Debug") {
    
      // Coverage report only works well in Debug mode, since optimisation might lead to underestimated coverage
      doCoverage(label, buildType)
      
      // Run valgrind only in Debug mode, since Release mode often leads to no-longer-matching suppressions
      doValgrind(label, buildType)
    }

    doInstall(label, buildType)
  }
}

/**********************************************************************************************************************/

def doBuild(ArrayList<String> dependencyList, String label, String buildType) {

  // obtain artefacts of dependencies
  script {
    dependencyList.each {
      copyArtifacts filter: "install-${it}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
    }
  }

  // unpack artefacts of dependencies into the Docker system root
  sh """
    if [ -d artefacts ]; then
      for a in artefacts/install-*-${label}-${buildType}.tgz ; do
        echo === \$a
        tar zxf \"\${a}\" -C /
      done
    fi
  """
    
  // start the build
  sh """
    sudo -u msk_jenkins mkdir -p build/build
    sudo -u msk_jenkins mkdir -p build/install
    cd build/build
    sudo -u msk_jenkins cmake ../.. -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=${buildType}
    sudo -u msk_jenkins make $MAKEOPTS
  """
}

/**********************************************************************************************************************/

def doTest(String label, String buildType) {

  // Run the tests via ctest
  sh """
    cd build/build
    sudo -u msk_jenkins ctest --no-compress-output -T Test
  """
    
  // Prefix test names with label and buildType, so we can distinguish them later
  sh """
    cd build/build
    sudo -u msk_jenkins sed -i Testing/*/Test.xml -e 's_\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$_\\1${label}.${buildType}.\\2\\3_'
  """
  
  // stash test result file for later publication
  stash includes: 'build/build/Testing/*/*.xml', name: "tests-${label}-${buildType}"
}

/**********************************************************************************************************************/

def doCoverage(String label, String buildType) {

  // Generate coverage report as HTML and also convert it into cobertura XML file
  sh """
    cd build/build
    sudo -u msk_jenkins make coverage
    sudo -u msk_jenkins /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info
  """
  
  // stash cobertura coverage report result for later publication
  stash includes: "build/build/coverage.xml", name: "cobertura-${label}-${buildType}"
  
  // publish HTML coverage report now, since it already allows publication of multiple distinguised reports
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

  // Run valgrind twice in memcheck and helgrind mode
  // Note: valgrind is run on ctest but for each test individually. This helps to distinguish later where errors
  // occurred. TODO: Check if --child-silent-after-fork=yes is a good choice in this context!
  sh """
    cd build/build
    TESTS=`ctest -N | grep "Test *\\#" | sed -e 's/^ *Test *\\#.*: //'`
    for test in \$TEST; do
      sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --child-silent-after-fork=yes --tool=memcheck --leak-check=full --xml=yes --xml-file=valgrind.\${test}.memcheck.valgrind ctest -R \${test}
      sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --child-silent-after-fork=yes --tool=helgrind --xml=yes --xml-file=valgrind.\${test}.helgrind.valgrind ctest -R \${test}
    done
  """

  // stash valgrind result files for later publication
  stash includes: '*.valgrind', name: "valgrind-${label}-${buildType}"
}

/**********************************************************************************************************************/

def doInstall(String label, String buildType) {

  // Install, but redirect files into the install directory (instead of installing into the system)
  sh """
    cd build/build
    sudo -u msk_jenkins make install DESTDIR=../install
  """
  
  // Generate tar ball of install directory - this will be the artefact used by our dependents
  sh """
    cd build/install
    sudo -u msk_jenkins tar zcf ../../install-${JOB_NAME}-${label}-${buildType}.tgz .
  """
  
  // Archive the artefact tar ball (even if other branches of this build failed - TODO: do we really want to do that?)
  archiveArtifacts artifacts: "install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
}

/**********************************************************************************************************************/

def doPublish() {

  // unstash result files into subdirectories
  dir('Ubuntu1604-Debug') {
    unstash "valgrind-Ubuntu1604-Debug"
    unstash "test-Ubuntu1604-Debug"
    unstash "cobertura-Ubuntu1604-Debug"
  }
  dir('Ubuntu1604-Release') {
    unstash "valgrind-Ubuntu1604-Release"
    unstash "test-Ubuntu1604-Release"
    unstash "cobertura-Ubuntu1604-Release"
  }
  dir('Ubuntu1804-Debug') {
    unstash "valgrind-Ubuntu1804-Debug"
    unstash "test-Ubuntu1804-Debug"
    unstash "cobertura-Ubuntu1804-Debug"
  }
  dir('Ubuntu1804-Release') {
    unstash "valgrind-Ubuntu1804-Release"
    unstash "test-Ubuntu1804-Release"
    unstash "cobertura-Ubuntu1804-Release"
  }
  dir('Tumbleweed-Debug') {
    unstash "valgrind-Tumbleweed-Debug"
    unstash "test-Tumbleweed-Debug"
    unstash "cobertura-Tumbleweed-Debug"
  }
  dir('Tumbleweed-Release') {
    unstash "valgrind-Tumbleweed-Release"
    unstash "test-Tumbleweed-Release"
    unstash "cobertura-Tumbleweed-Release"
  }

  // Run cppcheck and publish the result. Since this is a static analysis, we don't have to run it for each label
  sh """
    pwd
    mkdir -p build
    cppcheck --enable=all --xml --xml-version=2  -ibuild . 2> ./build/cppcheck.xml
  """
  publishCppcheck pattern: 'build/cppcheck.xml'

  // Scan for compiler warnings. This is scanning the entire build logs for all labels and build types  
  warnings canComputeNew: false, canResolveRelativePaths: false, categoriesPattern: '',
           consoleParsers: [[parserName: 'GNU Make + GNU C Compiler (gcc)']], defaultEncoding: '',
           excludePattern: '.*-Wstrict-aliasing.*', healthy: '', includePattern: '', messagesPattern: '',
           unHealthy: '', unstableTotalAll: '0'

  // publish test result
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: "*/build/build/Testing/*/*.xml") ])
  
  // publish valgrind result
  publishValgrind (
    failBuildOnInvalidReports: false,
    failBuildOnMissingReports: false,
    failThresholdDefinitelyLost: '',
    failThresholdInvalidReadWrite: '',
    failThresholdTotal: '',
    pattern: '*/*.valgrind',
    publishResultsForAbortedBuilds: false,
    publishResultsForFailedBuilds: false,
    sourceSubstitutionPaths: '',
    unstableThresholdDefinitelyLost: '',
    unstableThresholdInvalidReadWrite: '',
    unstableThresholdTotal: ''
  )
  
  // publish cobertura result
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "build/build/coverage.xml", conditionalCoverageTargets: '70, 0, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  
}

/**********************************************************************************************************************/

