/***********************************************************************************************************************

  steps is used from buildTestDeploy

***********************************************************************************************************************/

def doBuildTestDeploy(ArrayList<String> dependencyList, String label, String buildType) {

  // Add inactivity timeout of 10 minutes (build will be interrupted if 10 minutes no log output has been produced)
  timeout(activity: true, time: 10) {
  
    doBuild(dependencyList, label, buildType)
    doTest(label, buildType)
    doInstall(label, buildType)

  }
}

/**********************************************************************************************************************/

def doAnalysis(ArrayList<String> dependencyList, String label, String buildType) {

  // Add inactivity timeout of 60 minutes (build will be interrupted if 60 minutes no log output has been produced)
  timeout(activity: true, time: 60) {
    if(buildType == "Debug") {
    
      // Coverage report only works well in Debug mode, since optimisation might lead to underestimated coverage
      doCoverage(label, buildType)
      
      // Run valgrind only in Debug mode, since Release mode often leads to no-longer-matching suppressions
      doValgrind(label, buildType)
    }
  }
}

/**********************************************************************************************************************/

def doBuild(ArrayList<String> dependencyList, String label, String buildType) {
  echo("Starting build for ${label}-${buildType}")
  
  // Make sure, /var/run/lock/mtcadummy is writeable by msk_jenkins
  sh '''
    chmod ugo+rwX /var/run/lock/mtcadummy
  '''
  
  // Clean build directory. This removes any files which are not in the source code repository
  sh '''
    sudo -u msk_jenkins git clean -f -d -x
  '''
  
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
    sudo -u msk_jenkins ctest --no-compress-output $MAKEOPTS -T Test || true
  """
    
  // Prefix test names with label and buildType, so we can distinguish them later
  sh """
    cd build/build
    sudo -u msk_jenkins sed -i Testing/*/Test.xml -e 's_\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$_\\1${label}.${buildType}.\\2\\3_'
  """

  // Publish test result directly (works properly even with multiple publications from parallel branches)  
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: "build/build/Testing/*/*.xml") ])
}

/**********************************************************************************************************************/

def doCoverage(String label, String buildType) {
  echo("Generating coverage report for ${label}-${buildType}")

  // Generate coverage report as HTML and also convert it into cobertura XML file
  sh """
    cd build/build
    sudo -u msk_jenkins make coverage || true
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
  // 
  // First, find the test executables. Search for all CTestTestfile.cmake and look for add_test() inside. Resolve the
  // given names relative to the location of the CTestTestfile.cmake file.
  //
  // Note: we use ''' here instead of """ so we don't have to escape all the shell variables.
  sh '''
    cd build/build
    
    EXECLIST=""
    for testlist in `find -name CTestTestfile.cmake` ; do
      dir=`dirname $testlist`
      for test in `grep add_test "${testlist}" | sed -e 's_^[^"]*"__' -e 's/")$//'` ; do
        # $test is just the name of the test executable, without add_test etc.
        # It might be either relative to the directory the CTestTestfile.cmake is in, or absolute. Check for both.
        if [ -f "${test}" ]; then
          EXECLIST="${EXECLIST} `realpath ${test}`"
        elif [ -f "${dir}${test}" ]; then
          EXECLIST="${EXECLIST} `realpath ${dir}${test}`"
        fi
      done
    done
    
    for test in ${EXECLIST} ; do
      testname=`basename ${test}`
      sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --tool=memcheck --leak-check=full --xml=yes --xml-file=valgrind.${testname}.memcheck.valgrind ${test} &
      # sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --tool=helgrind --xml=yes --xml-file=valgrind.${testname}.helgrind.valgrind ${test}
    done
    wait
  '''

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

def doPublishBuildTestDeploy(ArrayList<String> builds) {

  // unstash result files into subdirectories
  builds.each {
    dir("${it}") {
      def (label, buildType) = it.tokenize('-')

      // get cobertura coverage result (only Debug)
      if(buildType == "Debug") {
        try {
          unstash "cobertura-${it}"
        }
        catch(all) {
          echo("Could not retreive stashed cobertura results for ${it}")
          currentBuild.result = 'FAILURE'
        }
      }
      
      // get valgrind result (only Debug)
      if(buildType == "Debug") {
        try {
          unstash "valgrind-${it}"
        }
        catch(all) {
          echo("Could not retreive stashed valgrind results for ${it}")
          currentBuild.result = 'FAILURE'
        }
      }

    }
  }
  
  sh '''
    find -name *.valgrind
  '''

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
           excludePattern: '', healthy: '', includePattern: '', messagesPattern: '.*-Wstrict-aliasing.*',
           unHealthy: '', unstableTotalAll: '0'
  
  // publish valgrind result
  publishValgrind (
    failBuildOnInvalidReports: true,
    failBuildOnMissingReports: true,
    failThresholdDefinitelyLost: '',
    failThresholdInvalidReadWrite: '',
    failThresholdTotal: '',
    pattern: '*/build/build/*.valgrind',
    publishResultsForAbortedBuilds: false,
    publishResultsForFailedBuilds: false,
    sourceSubstitutionPaths: '',
    unstableThresholdDefinitelyLost: '',
    unstableThresholdInvalidReadWrite: '',
    unstableThresholdTotal: '0'
  )
  
  // publish cobertura result
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "*/build/build/coverage.xml", conditionalCoverageTargets: '70, 0, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  
}

/**********************************************************************************************************************/

