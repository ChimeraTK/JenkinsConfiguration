/***********************************************************************************************************************

  steps is used from buildTestDeploy

***********************************************************************************************************************/

/**********************************************************************************************************************/

// helper function, recursively gather a deep list of dependencies
def gatherDependenciesDeep(ArrayList<String> dependencyList) {
  script {
    def deepList = dependencyList
    dependencyList.each {
      if(it != "") {
        copyArtifacts filter: "dependencyList.txt", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
        myFile = readFile(env.WORKSPACE+"/artefacts/dependencyList.txt")
        deepList.addAll(gatherDependenciesDeep(new ArrayList<String>(Arrays.asList(myFile.split("\n")))))
      }
    }
    return deepList.unique()
  }
}

/**********************************************************************************************************************/

// internal helper function, recursively wait until all dependencies are not building
def waitForDependencies_helper(ArrayList<String> deepDependencyList) {
  script {
    if(deepDependencyList.size() == 0 || deepDependencyList[0] == "") return true
    lock(resource: "build-${deepDependencyList[0]}", skipIfLocked: true) {
      def deepDependencyListTrunc = deepDependencyList
      deepDependencyListTrunc.remove(0)
      return waitForDependencies(deepDependencyListTrunc)
    }
    return false
  }
}

/**********************************************************************************************************************/

// helper function, recursively wait until all dependencies are not building
def waitForDependencies(ArrayList<String> deepDependencyList) {
  script {
    while(!waitForDependencies_helper(deepDependencyList)) {
      echo("Could not acquire lock. Retrying in 10 second...")
      sleep(10)
    }
  }
}
  
/**********************************************************************************************************************/

def doBuildTestDeploy(ArrayList<String> dependencyList, String label, String buildType, String gitUrl) {

  // prepare source directory and dependencies
  doPrepare(true, gitUrl)
  doDependencyArtefacts(dependencyList, label, buildType)

  // add inactivity timeout of 30 minutes (build will be interrupted if 30 minutes no log output has been produced)
  timeout(activity: true, time: 30) {
  
    // start build and tests, then generate artefact
    doBuild(label, buildType)
    if(buildType != "asan" && buildType != "tsan") {
      // tests for asan and tsan are run in the analysis jobs
      doTest(label, buildType)
    }

    // Run cppcheck only for focal-debug
    if((!env.DISABLE_CPPCHECK || env.DISABLE_CPPCHECK == '') && buildType == "Debug") {
        doCppcheck(label, buildType)
    }

    doInstall(label, buildType)

  }
}

/**********************************************************************************************************************/

def doAnalysis(String label, String buildType) {
  if(buildType == "Debug") {
    doPrepare(false)
    doBuilddirArtefact(label, buildType)

    // Add inactivity timeout of 60 minutes (build will be interrupted if 60 minutes no log output has been produced)
    timeout(activity: true, time: 60) {

      // Coverage report only works well in Debug mode, since optimisation might lead to underestimated coverage
      doCoverage(label, buildType)
      
      // Run valgrind only in Debug mode, since Release mode often leads to no-longer-matching suppressions
      // -> disable for now, doesn't work well and is probably replaced by asan
      //doValgrind(label, buildType)

    }
  }
  else if(buildType != "Release") {
    // asan and tsan modes
    doPrepare(false)
    doBuilddirArtefact(label, buildType)

    // Add inactivity timeout of 60 minutes (build will be interrupted if 60 minutes no log output has been produced)
    timeout(activity: true, time: 60) {
    
      // just run the tests
      doSanitizerAnalysis(label, buildType)

    }
  }
}

/**********************************************************************************************************************/

def doPrepare(boolean checkoutScm, String gitUrl='') {
  
  // configure sudoers file so we can change the PATH variable
  sh '''
    mv /etc/sudoers /etc/sudoers-backup
    grep -v secure_path /etc/sudoers-backup > /etc/sudoers
  '''

  // make sure all files and directories in the msk_jenkins home folder cna be accessed/written by msk_jenkins (especially the workspace)
  sh '''
    chown -R msk_jenkins /home/msk_jenkins
  '''
  
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
      if (env.BRANCH_NAME && env.BRANCH_NAME != '') {
          git branch: env.BRANCH_NAME, url: gitUrl
      } else {
          git gitUrl
      }
      sh 'sudo -H -E -u msk_jenkins git submodule update --init --recursive'
    }
    else {
      checkout scm
    }
    sh '''
      sudo -H -E -u msk_jenkins git clean -f -d -x
      sudo -H -E -u msk_jenkins mkdir /scratch/source
      sudo -H -E -u msk_jenkins cp -r * /scratch/source
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
          tar zxf \"artefacts/install-${dependency}-${label}-${buildType}.tgz\" -C / --keep-directory-symlink
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
        sudo -H -E -u msk_jenkins tar zxf \"\${a}\" -C /
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
        tar zxf \"\${a}\" -C /
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
      cat > /scratch/script <<EOF
#!/bin/bash
mkdir -p /scratch/build-${JOB_NAME}
mkdir -p /scratch/install
cd /scratch/build-${JOB_NAME}
# Required to find DOOCS
export PKG_CONFIG_PATH=/export/doocs/lib/pkgconfig
# We might run only part of the project from a sub-directory. If it is empty the trailing / does not confuse cmake
for VAR in \${JOB_VARIABLES}; do
  export \\`eval echo \\\${VAR}\\`
done
if [ "${buildType}" == "tsan" ]; then
  export CC="clang-10"
  export CXX="clang++-10"
elif [ "${buildType}" == "asan" ]; then
  export CC="clang-10"
  export CXX="clang++-10"
  export LSAN_OPTIONS=verbosity=1:log_threads=1
fi
cmake /scratch/source/\${RUN_FROM_SUBDIR} -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=${buildType} -DSUPPRESS_AUTO_DOC_BUILD=true \${CMAKE_EXTRA_ARGS}
make ${MAKEOPTS} VERBOSE=1
EOF
      cat /scratch/script
      chmod +x /scratch/script
      sudo -H -E -u msk_jenkins /scratch/script
    """
  }
  script {
    // copy compile_commands.json from build directory to workspace
    // any will do so the last one will win
    sh """
      cp /scratch/build-${JOB_NAME}/compile_commands.json "${WORKSPACE}" || true
    """
  }
  script {
    // generate and archive artefact from build directory (used for the analysis job)
    sh """
      sudo -H -E -u msk_jenkins tar zcf build-${JOB_NAME}-${label}-${buildType}.tgz /scratch
    """
    archiveArtifacts artifacts: "build-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
  }
}

/**********************************************************************************************************************/

def doTest(String label, String buildType) {
  if (env.SKIP_TESTS) {
    currentBuild.result = 'UNSTABLE'
    return
  }

  // Run the tests via ctest
  // Prefix test names with label and buildType, so we can distinguish them later
  // Copy test results files to the workspace, otherwise they are not available to the xunit plugin
  sh """
    cat > /scratch/script <<EOF
#!/bin/bash
cd /scratch/build-${JOB_NAME}
if [ -z "\${CTESTOPTS}" ]; then
  CTESTOPTS="\${MAKEOPTS}"
fi
for VAR in \${JOB_VARIABLES} \${TEST_VARIABLES}; do
   export \\`eval echo \\\${VAR}\\`
done
ctest --no-compress-output \${CTESTOPTS} -T Test -V || true
echo sed -i Testing/*/Test.xml -e 's|\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$|\\1${label}.${buildType}.\\2\\3|'
sed -i Testing/*/Test.xml -e 's|\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$|\\1${label}.${buildType}.\\2\\3|'
cp -r /scratch/build-${JOB_NAME}/Testing "${WORKSPACE}"
EOF
    cat /scratch/script
    chmod +x /scratch/script
    sudo -H -E -u msk_jenkins /scratch/script
  """

  // Publish test result directly (works properly even with multiple publications from parallel branches)  
  xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ],
         tools: [ CTest(pattern: "Testing/*/*.xml") ])
}

/**********************************************************************************************************************/

def doSanitizerAnalysis(String label, String buildType) {
  def parentJob = env.JOB_NAME[0..-10]     // remove "-analysis" from the job name, which is 9 chars long

  // Run the tests via ctest
  // Prefix test names with label and buildType, so we can distinguish them later
  // Copy test results files to the workspace, otherwise they are not available to the xunit plugin
  sh """
    cat > /scratch/script <<EOF
#!/bin/bash
cd /scratch/build-${parentJob}
if [ -z "\${CTESTOPTS}" ]; then
  CTESTOPTS="\${MAKEOPTS}"
fi
for VAR in \${JOB_VARIABLES} \${TEST_VARIABLES}; do
   export \\`eval echo \\\${VAR}\\`
done
export LSAN_OPTIONS="suppressions=/home/msk_jenkins/JenkinsConfiguration/sanitizer.suppressions/lsan.supp,\${LSAN_OPTIONS}"
export UBSAN_OPTIONS="suppressions=/home/msk_jenkins/JenkinsConfiguration/sanitizer.suppressions/ubsan.supp,\${UBSAN_OPTIONS}"
export TSAN_OPTIONS="second_deadlock_stack=1,suppressions=/home/msk_jenkins/JenkinsConfiguration/sanitizer.suppressions/tsan.supp,\${TSAN_OPTIONS}"
ctest --no-compress-output \${CTESTOPTS} -T Test -V
EOF
    cat /scratch/script
    chmod +x /scratch/script
    sudo -H -E -u msk_jenkins /scratch/script
  """
}

/**********************************************************************************************************************/

def doCoverage(String label, String buildType) {
  def parentJob = env.JOB_NAME[0..-10]     // remove "-analysis" from the job name, which is 9 chars long

  // Generate coverage report as HTML and also convert it into cobertura XML file
  sh """
    chown msk_jenkins -R /scratch
    cat > /scratch/script <<EOF
#!/bin/bash
cd /scratch/build-${parentJob}
for VAR in \${JOB_VARIABLES} \${TEST_VARIABLES}; do
   export \\`eval echo \\\${VAR}\\`
done
make coverage || true
python3 /common/lcov_cobertura-1.6/lcov_cobertura/lcov_cobertura.py coverage.info || true

cp -r coverage_html ${WORKSPACE} || true
cp -r coverage.xml ${WORKSPACE} || true
EOF
    cat /scratch/script
    chmod +x /scratch/script
    sudo -H -E -u msk_jenkins /scratch/script
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

    cd /home/msk_jenkins/JenkinsConfiguration
    cat valgrind.suppressions/common.supp valgrind.suppressions/${label}.supp > /scratch/valgrind.supp

    cat > /scratch/script <<EOF
#!/bin/bash
cd /scratch/build-${parentJob}

for testlist in \\`find -name CTestTestfile.cmake\\` ; do
  EXECLIST=""
  dir=\\`dirname "\\\${testlist}"\\`
  for test in \\`grep add_test "\\\${testlist}" | sed -e 's_^[^"]*"__' -e 's/")\\\$//'\\` ; do
    # \\\${test} is just the name of the test executable, without add_test etc.
    # It might be either relative to the directory the CTestTestfile.cmake is in, or absolute. Check for both.
    if [ -f "\\\${test}" ]; then
      EXECLIST="\\\${EXECLIST} \\`realpath \\\${test}\\`"
    elif [ -f "\\\${dir}/\\\${test}" ]; then
      EXECLIST="\\\${EXECLIST} \\`realpath \\\${dir}/\\\${test}\\`"
    fi
  done

  cd "\\\${dir}"
  for test in \\\${EXECLIST} ; do
    testname=\\`basename \\\${test}\\`
    if [ -z "\\`echo " \\\${valgrindExcludes} " | grep " \\\${testname} "\\`" ]; then
      valgrind --num-callers=99 --gen-suppressions=all --suppressions=/scratch/valgrind.supp                         \\
                                   --tool=memcheck --leak-check=full --undef-value-errors=yes --xml=yes              \\
                                   --xml-file=/scratch/build-${parentJob}/${label}.\\\${testname}.memcheck.valgrind  \\
                                   \\\${test}
    fi
  done
  cd /scratch/build-${parentJob}

done
EOF
    cat /scratch/script
    chmod +x /scratch/script
    sudo -H -E -u msk_jenkins /scratch/script

    sudo -H -E -u msk_jenkins cp /scratch/build-${parentJob}/*.valgrind "${WORKSPACE}"
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
    sudo -H -E -u msk_jenkins make install DESTDIR=../install
  
    cd /scratch/install
    mkdir -p scratch
    if [ -e /scratch/artefact.list ]; then
      cp /scratch/artefact.list scratch/dependencies.${JOB_NAME}.list
    fi
    sudo -H -E -u msk_jenkins tar zcf ${WORKSPACE}/install-${JOB_NAME}-${label}-${buildType}.tgz .
  """
  
  // Archive the artefact tar ball (even if other branches of this build failed - TODO: do we really want to do that?)
  archiveArtifacts artifacts: "install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
}

/**********************************************************************************************************************/

def doPublishBuildTestDeploy(ArrayList<String> builds) {

  // Note: this part runs only once per project, not for each branch!

  // Run cppcheck and publish the result. Since this is a static analysis, we don't have to run it for each label
  if(!env.DISABLE_CPPCHECK || env.DISABLE_CPPCHECK == '') {
    unstash "cppcheck.xml"
    publishCppcheck pattern: 'cppcheck.xml'
  }

  // Scan for compiler warnings. This is scanning the entire build logs for all labels and build types  
  recordIssues filters: [excludeMessage('.*-Wstrict-aliasing.*')], qualityGates: [[threshold: 1, type: 'TOTAL', unstable: true]], tools: [gcc()]
  
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
      // -> disable for now
      /*if(buildType == "Debug") {
        try {
          unstash "valgrind-${it}"
        }
        catch(all) {
          echo("Could not retreive stashed valgrind results for ${it}")
          currentBuild.result = 'FAILURE'
        }
      }*/
      
    }
  }
/*  
  // publish valgrind result
  publishValgrind (
    failBuildOnInvalidReports: true,
    failBuildOnMissingReports: true,
    failThresholdDefinitelyLost: '',
    failThresholdInvalidReadWrite: '',
    failThresholdTotal: '',
    pattern: '* / *.valgrind',
    publishResultsForAbortedBuilds: false,
    publishResultsForFailedBuilds: false,
    sourceSubstitutionPaths: '',
    unstableThresholdDefinitelyLost: '',
    unstableThresholdInvalidReadWrite: '',
    unstableThresholdTotal: '0'
  )
  */
  // publish cobertura result
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "*/coverage.xml", conditionalCoverageTargets: '70, 0, 0', failNoReports: false, failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  
}

/**********************************************************************************************************************/

def doCppcheck(String label, String buildType) {
  // Generate coverage report as HTML and also convert it into cobertura XML file
  sh """
    chown msk_jenkins -R /scratch
    cat > /scratch/script <<EOF
#!/bin/bash
cd /scratch/build-${JOB_NAME}-${label}-${buildType}
for VAR in \${JOB_VARIABLES} \${TEST_VARIABLES}; do
   export \\`eval echo \\\${VAR}\\`
done
if [ -e compile_commands.json ]; then
    cppcheck --inline-suppr --enable=all --xml --xml-version=2  --project=compile_commands.json 2>cppcheck.xml
else
    cppcheck --inline-suppr --enable=all --xml --xml-version=2  -ibuild -Iinclude /scratch/source 2>cppcheck.xml
fi
cp cppcheck.xml ${WORKSPACE} || true
EOF
    cat /scratch/script
    chmod +x /scratch/script
    sudo -H -E -u msk_jenkins /scratch/script
  """

  stash allowEmpty: true, includes: "cppcheck.xml", name: "cppcheck.xml"
}
