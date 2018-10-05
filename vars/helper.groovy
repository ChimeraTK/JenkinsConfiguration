/***********************************************************************************************************************

  steps is used from buildTestDeploy

***********************************************************************************************************************/

def doBuildTestDeploy(ArrayList<String> dependencyList, String label, String buildType) {

  doPrepare(true)
  doDependencyArtefacts(dependencyList, label, buildType)

  // Add inactivity timeout of 10 minutes (build will be interrupted if 10 minutes no log output has been produced)
  timeout(activity: true, time: 10) {
  
    doBuild(label, buildType)
    doTest(label, buildType)
    doInstall(label, buildType)

  }
}

/**********************************************************************************************************************/

def doAnalysis(ArrayList<String> dependencyList, String label, String buildType) {
  if(buildType == "Debug") {
    doPrepare(false)
    doBuilddirArtefact(label, buildType)

    // Add inactivity timeout of 60 minutes (build will be interrupted if 60 minutes no log output has been produced)
    timeout(activity: true, time: 60) {

      // Coverage report only works well in Debug mode, since optimisation might lead to underestimated coverage
      doCoverage(label, buildType)
      
      // Run valgrind only in Debug mode, since Release mode often leads to no-longer-matching suppressions
      doValgrind(label, buildType)

    }
  }
}

/**********************************************************************************************************************/

def doPrepare(boolean checkoutScm) {
  
  // Make sure, /var/run/lock/mtcadummy is writeable by msk_jenkins
  sh '''
    chmod ugo+rwX /var/run/lock/mtcadummy
  '''
  
  // Create scratch directory. Keep the absolute path fixed, so we can copy the build directory as an artefact for the
  // analysis job
  sh '''
    mkdir /scratch
    chown msk_jenkins /scratch
  '''
  
  // Check out source code
  if(checkoutScm) {
    checkout scm
    sh '''
      sudo -u msk_jenkins git clean -f -d -x
      sudo -u msk_jenkins mkdir /scratch/source
      sudo -u msk_jenkins cp -r * /scratch/source
    '''
  }

}

/**********************************************************************************************************************/

def doDependencyArtefacts(ArrayList<String> dependencyList, String label, String buildType) {
  echo("Obtaining dependency artefacts for ${label}-${buildType}")

  // obtain artefacts of dependencies
  script {
    sh """
      touch /scratch/artefact.list
    """
    echo("Getting artefacts...")
    dependencyList.each {
      sh """
        echo "${it}" >> /scratch/artefact.list
      """
      copyArtifacts filter: "install-${it}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
    }
    echo("Done getting artefacts.")
  }

  // unpack artefacts of dependencies into the Docker system root
  echo("Unpacking artefacts...")
  sh """
    if ls artefacts/install-*-${label}-${buildType}.tgz 1>/dev/null 2>&1; then
      for a in artefacts/install-*-${label}-${buildType}.tgz ; do
        tar zxvf \"\${a}\" -C /
      done
    fi
  """

}

/**********************************************************************************************************************/

def doBuilddirArtefact(String label, String buildType) {
  echo("Obtaining build directory artefact for ${label}-${buildType}")
  
  // obtain artefacts of dependencies
  script {
    def parentJob = env.JOB_NAME[0..-10]     // remove "-analysis" from the job name, which is 9 chars long
    copyArtifacts filter: "build-${parentJob}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${parentJob}", selector: lastSuccessful(), target: "artefacts"
  }

  // unpack artefact into the Docker system root (should only write files to /scratch, which is writable by msk_jenkins)
  sh """
    for a in artefacts/build-*-${label}-${buildType}.tgz ; do
      sudo -u msk_jenkins tar zxvf \"\${a}\" -C /
    done
  """

  // obtain artefacts of dependencies (from /scratch/artefact.list)
  script {
    echo("Getting dependency artefacts...")
    sh """
      cp /scratch/artefact.list ${WORKSPACE}/artefact.list
    """
    myFile = readFile(env.WORKSPACE+"/artefact.list")
    myFile.split("\n").each {
      if( it != "" ) {
        copyArtifacts filter: "install-${it}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
      }
    }
    echo("Done dependency getting artefacts.")
  }

  // unpack artefacts of dependencies into the Docker system root
  sh """
    if ls artefacts/install-*-${label}-${buildType}.tgz 1>/dev/null 2>&1; then
      for a in artefacts/install-*-${label}-${buildType}.tgz ; do
        tar zxvf \"\${a}\" -C /
      done
    fi
  """

}

/**********************************************************************************************************************/

def doBuild(String label, String buildType) {
  echo("Starting build for ${label}-${buildType}")
  
  // start the build
  echo("Starting actual build...")
  sh """
    sudo -u msk_jenkins mkdir -p /scratch/build
    sudo -u msk_jenkins mkdir -p /scratch/install
    cd /scratch/build
    sudo -u msk_jenkins cmake /scratch/source -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=${buildType}
    sudo -u msk_jenkins make $MAKEOPTS
  """
  echo("Done with the build.")
  
  // generate and archive artefact from build directory (used for the analysis job)
  sh """
    sudo -u msk_jenkins tar zcf build-${JOB_NAME}-${label}-${buildType}.tgz /scratch
  """
  archiveArtifacts artifacts: "build-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false

}

/**********************************************************************************************************************/

def doTest(String label, String buildType) {
  echo("Starting tests for ${label}-${buildType}")

  // Run the tests via ctest
  sh """
    cd /scratch/build
    sudo -u msk_jenkins ctest --no-compress-output $MAKEOPTS -T Test || true
  """
    
  // Prefix test names with label and buildType, so we can distinguish them later
  sh """
    cd /scratch/build
    sudo -u msk_jenkins sed -i Testing/*/Test.xml -e 's_\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$_\\1${label}.${buildType}.\\2\\3_'
  """
    
  // Copy test results files to the workspace, otherwise they are not available to the xunit plugin
  sh """
    sudo -u msk_jenkins cp -r /scratch/build/Testing .
  """

  // Publish test result directly (works properly even with multiple publications from parallel branches)  
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: "Testing/*/*.xml") ])
}

/**********************************************************************************************************************/

def doCoverage(String label, String buildType) {
  echo("Generating coverage report for ${label}-${buildType}")

  // Generate coverage report as HTML and also convert it into cobertura XML file
  sh """
    cd /scratch/build
    sudo -u msk_jenkins make coverage || true
    sudo -u msk_jenkins /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info
    
    sudo -u msk_jenkins cp -r coverage_html ${WORKSPACE}
    sudo -u msk_jenkins cp -r coverage.xml ${WORKSPACE}
  """
  
  // stash cobertura coverage report result for later publication
  stash includes: "coverage.xml", name: "cobertura-${label}-${buildType}"
  
  // publish HTML coverage report now, since it already allows publication of multiple distinguised reports
  publishHTML (target: [
      allowMissing: false,
      alwaysLinkToLastBuild: false,
      keepAll: false,
      reportDir: "coverage_html",
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
    cd /scratch/build
    
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
      cd `dirname ${test}`
      sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --tool=memcheck --leak-check=full --xml=yes --xml-file=valgrind.${testname}.memcheck.valgrind ${test}
      # sudo -u msk_jenkins valgrind --gen-suppressions=all --trace-children=yes --tool=helgrind --xml=yes --xml-file=valgrind.${testname}.helgrind.valgrind ${test}
    done
    wait
  '''
  
  // stash valgrind result files for later publication
  sh """
    sudo -u msk_jenkins cp /scratch/build/*.valgrind .
  """
  stash includes: '*.valgrind', name: "valgrind-${label}-${buildType}"
}

/**********************************************************************************************************************/

def doInstall(String label, String buildType) {
  echo("Generating artefacts for ${label}-${buildType}")

  // Install, but redirect files into the install directory (instead of installing into the system)
  sh """
    cd /scratch/build
    sudo -u msk_jenkins make install DESTDIR=../install
  """
  
  // Generate tar ball of install directory - this will be the artefact used by our dependents
  sh """
    cd /scratch/install
    sudo -u msk_jenkins tar zcf ${WORKSPACE}/install-${JOB_NAME}-${label}-${buildType}.tgz .
  """
  
  // Archive the artefact tar ball (even if other branches of this build failed - TODO: do we really want to do that?)
  archiveArtifacts artifacts: "install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
}

/**********************************************************************************************************************/

def doPublishBuildTestDeploy(ArrayList<String> builds) {

  // Note: this part runs only once per project, not for each branch!

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
  
}

/**********************************************************************************************************************/

def doPublishAnalysis(ArrayList<String> builds) {

  // Note: this part runs only once per project, not for each branch!

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
  
  // publish valgrind result
  publishValgrind (
    failBuildOnInvalidReports: true,
    failBuildOnMissingReports: true,
    failThresholdDefinitelyLost: '',
    failThresholdInvalidReadWrite: '',
    failThresholdTotal: '',
    pattern: '*/*.valgrind',
    publishResultsForAbortedBuilds: false,
    publishResultsForFailedBuilds: false,
    sourceSubstitutionPaths: '',
    unstableThresholdDefinitelyLost: '',
    unstableThresholdInvalidReadWrite: '',
    unstableThresholdTotal: '0'
  )
  
  // publish cobertura result
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "*/coverage.xml", conditionalCoverageTargets: '70, 0, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  
}

/**********************************************************************************************************************/

