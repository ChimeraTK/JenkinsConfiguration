/***********************************************************************************************************************

 Pipeline script for Jenkins CI tests of ChimeraTK libraries and DESY MSK projects


 Usage:
 
 - In the git repository of the project source create a file named ".jenkinsfile"
 - Place the following content into that file (no indentation):
    @Library('ChimeraTK') _
    buildTestDeploy(['dependency1', 'dependency2']) 
  - Beware the underscore at the end of the first line!
  - The list of dependencies is optional, just call buildTestDeploy() if there are no dependencies
  - The dependencies specify the Jenkins project names of which the artefacts should be obtained and unpacked into
    the root directory of the Docker environment before starting the build.
 

 General comments:
 
 - Builds and tests are run inside a Docker container to have different test environments
 - We execute all builds/tests twice per test environment - once for Debug and once for Release. Each execution is
   called a "branch".
 - Docker only virtualises the system without the kernel - the PCIe dummy driver is therefore shared!
 - Most Jenkins plugins do not support their result publication executed once per branch, thus we stash the result files
   and execute the publication later for all branches together.
   
 - Important: It seems that some echo() are necessary, since otherwise the build is failing without error message. The
   impression might be wrong and the reasons are not understood. A potential explanation might be that sometimes empty
   scripts (e.g. the part downloading the artefacts when no artefacts are present) lead to failure and an echo() makes
   it not empty. The explanation is certainly not complete, as only one of the branches fails in these cases. This
   should be investigated further.

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
def call(ArrayList<String> dependencyList) {

  pipeline {
    agent none
    stages {
      stage('build') {
        // Run the build stages for all labels + build types in parallel, each in a separate docker container
        steps {
          script {
            def builds = [ 'xenial-Debug',
                           'xenial-Release',
                           'bionic-Debug',
                           'bionic-Release',
                           'tumbleweed-Debug',
                           'tumbleweed-Release' ]
            parallel builds.collectEntries { ["${it}" : transformIntoStep(dependencyList, it)] }
          }
        }
      } // end stage build
    } // end stages
    post {
      always {
        node('Docker') {
          doPublish()
        }
      } // end always
    } // end post
  } // end pipeline
}

/**********************************************************************************************************************/

def transformIntoStep(ArrayList<String> dependencyList, String buildName) {
  // split the build name at the '-'
  def (label, buildType) = buildName.tokenize('-')
  // we need to return a closure here, which is then passed to parallel() for execution
  return {
    stage(buildName) {
      node('Docker') {
        // we need root access inside the container and access to the dummy pcie devices of the host
        def dockerArgs = "-u 0 --device=/dev/mtcadummys0 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6"
        docker.image("builder:${label}").inside(dockerArgs) {
          doAll(dependencyList, label, buildType)
        }
      }
    }
  }
}

/**********************************************************************************************************************/

def doAll(ArrayList<String> dependencyList, String label, String buildType) {

  // Add inactivity timeout of 10 minutes (build will be interrupted if 30 minutes no log output has been produced)
  timeout(activity: true, time: 30) {

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
  echo("Starting build for ${label}-${buildType}")

  // obtain artefacts of dependencies
  script {
    echo("Getting artefacts...")
    dependencyList.each {
      copyArtifacts filter: "install-${it}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
    }
    echo("Done getting artefacts.")
  }

  // unpack artefacts of dependencies into the Docker system root
  echo("Unpacking artefacts...")
  sh """
    if [ -d artefacts ]; then
      for a in artefacts/install-*-${label}-${buildType}.tgz ; do
        tar zxvf \"\${a}\" -C /
      done
    fi
  """
    
  // start the build
  echo("Starting actual build...")
  sh """
    sudo -u msk_jenkins mkdir -p build/build
    sudo -u msk_jenkins mkdir -p build/install
    cd build/build
    sudo -u msk_jenkins cmake ../.. -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=${buildType}
    sudo -u msk_jenkins make $MAKEOPTS
  """
  echo("Done with the build.")
}

/**********************************************************************************************************************/

def doTest(String label, String buildType) {
  echo("Starting tests for ${label}-${buildType}")

  // Run the tests via ctest
  sh """
    cd build/build
    sudo -u msk_jenkins ctest --no-compress-output $MAKEOPTS -T Test
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
  echo("Generating coverage report for ${label}-${buildType}")

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
  echo("Running valgrind for ${label}-${buildType}")

  // Run valgrind twice in memcheck and helgrind mode
  // Note: valgrind is run on ctest but for each test individually. This helps to distinguish later where errors
  // occurred. TODO: Check if --child-silent-after-fork=yes is a good choice in this context!
  sh """
    cd build/build
    for test in `ctest -N | grep "Test *\\#" | sed -e 's/^ *Test *\\#.*: //'` ; do
      sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --child-silent-after-fork=yes --tool=memcheck --leak-check=full --xml=yes --xml-file=valgrind.\${test}.memcheck.valgrind ctest -V -R \${test} &
      # sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --child-silent-after-fork=yes --tool=helgrind --xml=yes --xml-file=valgrind.\${test}.helgrind.valgrind ctest -V -R \${test}
    done
    wait
  """

  // stash valgrind result files for later publication
  stash includes: 'build/build/*.valgrind', name: "valgrind-${label}-${buildType}"
}

/**********************************************************************************************************************/

def doInstall(String label, String buildType) {
  echo("Generating artefacts for ${label}-${buildType}")

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
    try {
      unstash "test-Ubuntu1604-Debug"
      unstash "cobertura-Ubuntu1604-Debug"
      unstash "valgrind-Ubuntu1604-Debug"
    }
    catch(all) {
      currentBuild.result = 'FAILURE'
    }
  }
  dir('Ubuntu1604-Release') {
    try {
      unstash "test-Ubuntu1604-Release"
      unstash "cobertura-Ubuntu1604-Release"
      unstash "valgrind-Ubuntu1604-Release"
    }
    catch(all) {
      currentBuild.result = 'FAILURE'
    }
  }
  dir('Ubuntu1804-Debug') {
    try {
      unstash "test-Ubuntu1804-Debug"
      unstash "cobertura-Ubuntu1804-Debug"
      unstash "valgrind-Ubuntu1804-Debug"
    }
    catch(all) {
      currentBuild.result = 'FAILURE'
    }
  }
  dir('Ubuntu1804-Release') {
    try {
      unstash "test-Ubuntu1804-Release"
      unstash "cobertura-Ubuntu1804-Release"
      unstash "valgrind-Ubuntu1804-Release"
    }
    catch(all) {
      currentBuild.result = 'FAILURE'
    }
  }
  dir('Tumbleweed-Debug') {
    try {
      unstash "test-Tumbleweed-Debug"
      unstash "cobertura-Tumbleweed-Debug"
      unstash "valgrind-Tumbleweed-Debug"
    }
    catch(all) {
      currentBuild.result = 'FAILURE'
    }
  }
  dir('Tumbleweed-Release') {
    try {
      unstash "test-Tumbleweed-Release"
      unstash "cobertura-Tumbleweed-Release"
      unstash "valgrind-Tumbleweed-Release"
    }
    catch(all) {
      currentBuild.result = 'FAILURE'
    }
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

