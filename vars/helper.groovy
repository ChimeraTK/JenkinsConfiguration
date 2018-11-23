/***********************************************************************************************************************

  steps is used from buildTestDeploy

***********************************************************************************************************************/

/**********************************************************************************************************************/

def doBuildTestDeploy(ArrayList<String> dependencyList, String label, String buildType, String gitUrl) {

  // prepare source directory and dependencies
  doPrepare(true, gitUrl)
  doDependencyArtefacts(dependencyList, label, buildType)

  // add inactivity timeout of 10 minutes (build will be interrupted if 10 minutes no log output has been produced)
  timeout(activity: true, time: 10) {
  
    // start build and tests, then generate artefact
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

def doPrepare(boolean checkoutScm, String gitUrl='') {
  
  // Make sure, /var/run/lock/mtcadummy is writeable by msk_jenkins.
  // Create scratch directory. Keep the absolute path fixed, so we can copy the build directory as an artefact for the
  // analysis job
  sh '''
    chmod ugo+rwX /var/run/lock/mtcadummy
    mkdir /scratch
    chown msk_jenkins /scratch
  '''
  
  // Check out source code
  if(checkoutScm) {
    if(gitUrl != '') {
      git gitUrl
    }
    else {
      checkout scm
    }
    sh '''
      sudo -u msk_jenkins git clean -f -d -x
      sudo -u msk_jenkins mkdir /scratch/source
      sudo -u msk_jenkins cp -r * /scratch/source
    '''
  }

}

/**********************************************************************************************************************/

def doDependencyArtefacts(ArrayList<String> dependencyList, String label, String buildType, ArrayList<String> obtainedArtefacts=[]) {

  // obtain artefacts of dependencies
  script {
    dependencyList.each {
      def dependency = it
      if( dependency != "" && obtainedArtefacts.find{it == dependency} != dependency ) {
        copyArtifacts filter: "install-${dependency}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${dependency}", selector: lastSuccessful(), target: "artefacts"
        obtainedArtefacts.add(dependency)
        sh """
          tar zxf \"artefacts/install-${dependency}-${label}-${buildType}.tgz\" -C /
          touch /scratch/dependencies.${dependency}.list
          cp /scratch/dependencies.${dependency}.list ${WORKSPACE}/artefact.list
          touch /scratch/artefact.list
          echo "${dependency}" >> /scratch/artefact.list
        """
        myFile = readFile(env.WORKSPACE+"/artefact.list")
        doDependencyArtefacts(new ArrayList<String>(Arrays.asList(myFile.split("\n"))), label, buildType, obtainedArtefacts)
      }
    }
    sh """
      chown -R msk_jenkins /scratch
    """
  }

}

/**********************************************************************************************************************/

def doBuilddirArtefact(String label, String buildType) {
  
  // obtain artefacts of dependencies
  script {
    def parentJob = env.JOB_NAME[0..-10]     // remove "-analysis" from the job name, which is 9 chars long
    copyArtifacts filter: "build-${parentJob}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${parentJob}", selector: lastSuccessful(), target: "artefacts"

    // Unpack artefact into the Docker system root (should only write files to /scratch, which is writable by msk_jenkins).
    // Then obtain artefacts of dependencies (from /scratch/artefact.list)
    sh """
      for a in artefacts/build-*-${label}-${buildType}.tgz ; do
        sudo -u msk_jenkins tar zxvf \"\${a}\" -C /
      done

      touch /scratch/artefact.list
      cp /scratch/artefact.list ${WORKSPACE}/artefact.list
    """
    myFile = readFile(env.WORKSPACE+"/artefact.list")
    myFile.split("\n").each {
      if( it != "" ) {
        copyArtifacts filter: "install-${it}-${label}-${buildType}.tgz", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
      }
    }
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
  catchError {
    // start the build
    sh """
      chown -R msk_jenkins /scratch
      sudo -u msk_jenkins mkdir -p /scratch/build-${JOB_NAME}
      sudo -u msk_jenkins mkdir -p /scratch/install
      cd /scratch/build-${JOB_NAME}
      sudo -u msk_jenkins cmake /scratch/source -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=${buildType} -DSUPPRESS_AUTO_DOC_BUILD=true \${CMAKE_EXTRA_ARGS}
      sudo -u msk_jenkins make $MAKEOPTS
    """
  }
  script {
    // generate and archive artefact from build directory (used for the analysis job)
    sh """
      sudo -u msk_jenkins tar zcf build-${JOB_NAME}-${label}-${buildType}.tgz /scratch
    """
    archiveArtifacts artifacts: "build-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
  }
}

/**********************************************************************************************************************/

def doTest(String label, String buildType) {

  // Run the tests via ctest
  // Prefix test names with label and buildType, so we can distinguish them later
  // Copy test results files to the workspace, otherwise they are not available to the xunit plugin
  sh """
    cd /scratch/build-${JOB_NAME}
    if [ -z "\${CTESTOPTS}" ]; then
      CTESTOPTS="\${MAKEOPTS}"
    fi
    sudo -u msk_jenkins ctest --no-compress-output \${CTESTOPTS} -T Test -V || true
    sudo -u msk_jenkins sed -i Testing/*/Test.xml -e 's_\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$_\\1${label}.${buildType}.\\2\\3_'
    sudo -u msk_jenkins cp -r /scratch/build-${JOB_NAME}/Testing "${WORKSPACE}"
  """

  // Publish test result directly (works properly even with multiple publications from parallel branches)  
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: "Testing/*/*.xml") ])
}

/**********************************************************************************************************************/

def doCoverage(String label, String buildType) {
  def parentJob = env.JOB_NAME[0..-10]     // remove "-analysis" from the job name, which is 9 chars long

  // Generate coverage report as HTML and also convert it into cobertura XML file
  sh """
    chown msk_jenkins -R /scratch
    cd /scratch/build-${parentJob}
    sudo -u msk_jenkins make coverage || true
    sudo -u msk_jenkins /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info || true
    
    sudo -u msk_jenkins cp -r coverage_html ${WORKSPACE} || true
    sudo -u msk_jenkins cp -r coverage.xml ${WORKSPACE} || true
  """
  
  // stash cobertura coverage report result for later publication
  stash allowEmpty: true, includes: "coverage.xml", name: "cobertura-${label}-${buildType}"
  
  // publish HTML coverage report now, since it already allows publication of multiple distinguised reports
  publishHTML (target: [
      allowMissing: true,
      alwaysLinkToLastBuild: false,
      keepAll: false,
      reportDir: "coverage_html",
      reportFiles: 'index.html',
      reportName: "LCOV coverage report for ${label} ${buildType}"
  ])  
}

/**********************************************************************************************************************/

def doValgrind(String label, String buildType) {
  def parentJob = env.JOB_NAME[0..-10]     // remove "-analysis" from the job name, which is 9 chars long

  // Run valgrind twice in memcheck and helgrind mode
  // 
  // First, find the test executables. Search for all CTestTestfile.cmake and look for add_test() inside. Resolve the
  // given names relative to the location of the CTestTestfile.cmake file.
  //
  // We execute the tests in the directory where CTestTestfile.cmake is which lists them.
  sh """
    chown msk_jenkins -R /scratch
    cd /scratch/build-${parentJob}
    
    EXECLIST=""
    for testlist in `find -name CTestTestfile.cmake` ; do
      dir=`dirname "\${testlist}"`
      for test in `grep add_test "\${testlist}" | sed -e 's_^[^"]*"__' -e 's/")\$//'` ; do
        # \${test} is just the name of the test executable, without add_test etc.
        # It might be either relative to the directory the CTestTestfile.cmake is in, or absolute. Check for both.
        if [ -f "\${test}" ]; then
          EXECLIST="\${EXECLIST} `realpath \${test}`"
        elif [ -f "\${dir}/\${test}" ]; then
          EXECLIST="\${EXECLIST} `realpath \${dir}/\${test}`"
        fi
      done
    
      cd "\${dir}"
      for test in \${EXECLIST} ; do
        testname=`basename \${test}`
        if [ -z "`echo " \${valgrindExcludes} " | grep " \${testname} "`" ]; then
          sudo -u msk_jenkins valgrind --gen-suppressions=all --suppressions=/common/valgrind.suppressions/ChimeraTK.supp --tool=memcheck --leak-check=full --undef-value-errors=yes --xml=yes --xml-file=/scratch/build-${parentJob}/valgrind.\${testname}.memcheck.valgrind \${test}
          # sudo -u msk_jenkins valgrind --gen-suppressions=all --suppressions=/common/valgrind.suppressions/ChimeraTK.sup --tool=helgrind --xml=yes --xml-file=/scratch/build-${parentJob}/valgrind.\${testname}.helgrind.valgrind \${test}
        fi
      done
      cd /scratch/build-${parentJob}

    done
  
    sudo -u msk_jenkins cp /scratch/build-${parentJob}/*.valgrind "${WORKSPACE}"
  """
  // stash valgrind result files for later publication
  stash includes: '*.valgrind', name: "valgrind-${label}-${buildType}"
}

/**********************************************************************************************************************/

def doInstall(String label, String buildType) {

  // Install, but redirect files into the install directory (instead of installing into the system)
  // Generate tar ball of install directory - this will be the artefact used by our dependents
  sh """
    cd /scratch/build-${JOB_NAME}
    sudo -u msk_jenkins make install DESTDIR=../install
  
    cd /scratch/install
    mkdir -p scratch
    if [ -e /scratch/artefact.list ]; then
      cp /scratch/artefact.list scratch/dependencies.${JOB_NAME}.list
    fi
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
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "*/coverage.xml", conditionalCoverageTargets: '70, 0, 0', failNoReports: false, failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  
}

/**********************************************************************************************************************/

