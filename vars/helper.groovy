/***********************************************************************************************************************

  steps is used from buildTestDeploy

***********************************************************************************************************************/

ArrayList<String> BUILD_PLAN = []
String BRANCH_UNDER_TEST = ""
def DEPENDENCY_BUILD_NUMBERS = [:]

/**********************************************************************************************************************/

def setParameters() {
  properties([
    parameters([
      string(name: 'BRANCH_UNDER_TEST', description: 'LEAVE AT DEFAULT! Jenkins project name for the branch to be tested', defaultValue: JOB_NAME),
      string(name: 'BUILD_PLAN', description: 'LEAVE AT DEFAULT! JSON object with list of downstream projects to build', defaultValue: '[]'),
      string(name: 'DEPENDENCY_BUILD_NUMBERS', description: 'LEAVE AT DEFAULT! JSON object with map of dependency build numbers to use', defaultValue: '{}'),
    ])
  ])

  BRANCH_UNDER_TEST = params.BRANCH_UNDER_TEST
  BUILD_PLAN = readJSON(text: params.BUILD_PLAN)  // new groovy.json.JsonSlurper().parseText(params.BUILD_PLAN)
  DEPENDENCY_BUILD_NUMBERS = readJSON(text: params.DEPENDENCY_BUILD_NUMBERS) // new groovy.json.JsonSlurper().parseText(params.DEPENDENCY_BUILD_NUMBERS)
}

/**********************************************************************************************************************/

// helper function, convert dependency name as listed in the .jenkinsfile into a Jenkins project name
def dependencyToJenkinsProject(String dependency, boolean forceBranches = false) {
  if(dependency.contains('@')) {
    // get rid of build number if specified
    dependency = dependency.split('@')[0]
  }
  def dependencyProjectName = dependency
  def (dependencyFolder, dependencyProject) = dependencyProjectName.split('/',2)
  dependencyProject = dependencyProject.replace('/','%2F')
  dependency = "${dependencyFolder}/${dependencyProject}"

  def jobType = env.JOB_TYPE
  jobType = jobType?.minus("-testing")
  jobType = jobType?.minus("-analysis")
  
  branch = "master"

  def dependencyUnderTest = jekinsProjectToDependency(BRANCH_UNDER_TEST)

  // Note: BUILD_PLAN.indexOf(dependency) does not deliver same result as BUILD_PLAN.findIndexOf{ it == dependency }!
  if( !env.JOB_TYPE.startsWith('branches') ||
      ( !forceBranches && (BUILD_PLAN.findIndexOf{ it == dependency } < 0 || BRANCH_UNDER_TEST == JOB_NAME) &&
        dependencyUnderTest != dependency)                                                         ) {
    jobType = "fasttrack"
  }
  else { 
    def (butFolder, butType, butProject, butBranch) = BRANCH_UNDER_TEST.split('/')
    if(butFolder == dependencyFolder && butType == jobType && butProject == dependencyProject) {
      branch = butBranch
      echo("Dependency ${dependency} matches branch under test.")
    }
  }
  
  dependencyProjectName = "${dependencyFolder}/${jobType}/${dependencyProject}/${branch}"
  return dependencyProjectName
}


/**********************************************************************************************************************/

// helper function, convert Jenkins project name into dependency name (as listed e.g. in the .jenkinsfile)
def jekinsProjectToDependency(String jenkinsProject) {
  def projectSplit = jenkinsProject.split('/')

  if(projectSplit.size() != 4) {
    error("Jenkins project name '${jenkinsProject}' has the wrong format for jekinsProjectToDependency()")
  }
  def (folder, type, project, branch) = projectSplit
  return "${folder}/${project}"
}

/**********************************************************************************************************************/

// helper function, recursively gather a deep list of dependencies
def gatherDependenciesDeep(ArrayList<String> dependencyList) {
  script {
    def stringList = dependencyList.join(' ')
    def output = sh ( script: "/home/msk_jenkins/findDependenciesDeep ${stringList}", returnStdout: true ).trim()    
    def deepList = new ArrayList<String>(Arrays.asList(output.split("\n")))
    return deepList.unique()
  }
}

/**********************************************************************************************************************/

// Helper function to get list of downstream projects
def findReverseDependencies(String project) {
  def projectCleaned = project.replace("/","_")
  sh """
    if [ -d /home/msk_jenkins/dependency-database/forward/${projectCleaned} ]; then
      cd /home/msk_jenkins/dependency-database/forward/${projectCleaned}
      cat *
      cat * > "${WORKSPACE}/dependees.txt"
    else
      # no downstream projects present: create empty list
      rm -f ${WORKSPACE}/dependees.txt
      touch ${WORKSPACE}/dependees.txt
    fi
  """
  def revDeps = readFile(env.WORKSPACE+"/dependees.txt").tokenize("\n")
  sh """
    rm -f ${WORKSPACE}/dependees.txt
  """
  return revDeps
}

/**********************************************************************************************************************/

def generateBuildPlan() {
  def depName = jekinsProjectToDependency(JOB_NAME)
  sh """
    cd ${WORKSPACE}
    /home/msk_jenkins/generateBuildPlan ${depName}
  """
  def text = readFile(env.WORKSPACE+"/buildplan.txt")
  return new groovy.json.JsonSlurper().parseText(text.replace("'", '"'))
}

/**********************************************************************************************************************/

def getArtefactName(boolean forReading, String basename, String label, String buildType, String dependency = jekinsProjectToDependency(JOB_NAME)) {
  // Compute name for artafact in local file storage on the build node. This saves time and is more flexible than using
  // Jenkins archiveArtifact/copyArtifact, but won't work when using multiple build nodes.
  //
  // "basename" is the filename with extension but without path. It should not contain any job/build specific parts, as
  // this will be taken care of in the path. Typical values are "build" and "install".
  //
  // This function also creates the directory if not yet existing.

  def dependencyNoBuildNo = dependency
  if(dependencyNoBuildNo.contains('@')) {
    // get rid of build number if specified
    dependencyNoBuildNo = dependency.split('@')[0]
  }
  
  def jobName = dependencyToJenkinsProject(dependencyNoBuildNo)
  def JOBNAME_CLEANED=jobName.replace('/','_')

  def path = "/home/msk_jenkins/artefacts/${JOBNAME_CLEANED}/${label}/${buildType}"
  
  def buildNumer = null
  if(forReading) {

    if(dependency.contains('@')) {
      buildNumber = dependency.split('@',2)[1]
      echo("Build number from dependency name: ${dependency} -> ${buildNumber}")
    }
    else if(DEPENDENCY_BUILD_NUMBERS.containsKey(dependencyNoBuildNo)) {
      // looking for build name of job which triggered us
      buildNumber = DEPENDENCY_BUILD_NUMBERS[dependencyNoBuildNo]
      echo("Build number from upstream trigger: ${dependency} -> ${buildNumber}")
    }
    else {
      // determine latest available build
      buildNumber = sh ( script: "ls ${path} | sort -n | tail -n1", returnStdout: true ).trim()
      echo("Build number from latest build: ${dependency} -> ${buildNumber}")
      DEPENDENCY_BUILD_NUMBERS[dependencyNoBuildNo] = buildNumber
    }
  }
  else {
    buildNumber = BUILD_NUMBER
  }
  
  path = path+"/"+buildNumber

  if(!forReading) {
    sh """
      sudo -u msk_jenkins mkdir -p ${path}
    """
  }
  else {
    sh """
      if [ ! -d ${path} ]; then
        echo "Dependency directory does not exist: path=${path} with label=${label} buildType=${buildType} dependency=${dependency} dependencyNoBuildNo=${dependencyNoBuildNo} jobName=${jobName} JOBNAME_CLEANED=${JOBNAME_CLEANED} buildNumber=${buildNumber} env.JOB_TYPE=${env.JOB_TYPE}"
        exit 1
      fi
    """
  }

  return "${path}/${basename}"
}

/**********************************************************************************************************************/

def getBuildNumberFromArtefactFileName(String fileName) {
  def components = fileName.split('/')
  return components[components.size()-2]
}

/**********************************************************************************************************************/

def doBuildAndDeploy(ArrayList<String> dependencyList, String label, String buildType, String gitUrl) {

  // prepare source directory and dependencies
  doPrepare(true, gitUrl)
  doDependencyArtefacts(dependencyList, label, buildType, jekinsProjectToDependency(JOB_NAME))

  // add inactivity timeout of 30 minutes (build will be interrupted if 30 minutes no log output has been produced)
  timeout(activity: true, time: 30) {
 
    // perform build and generate build artefact
    doBuild(label, buildType)

    // deploy and generate deployment artefact
    doDeploy(label, buildType)

  }
}

/**********************************************************************************************************************/

def doTesting(String label, String buildType) {

  // prepare source directory and dependencies
  doPrepare(false)
  doBuilddirArtefact(label, buildType)

  // add inactivity timeout of 30 minutes (build will be interrupted if 30 minutes no log output has been produced)
  timeout(activity: true, time: 30) {
 
    // run tests
    doRunTests(label, buildType)

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
  // Job name without slashes, to be used as filename/directory component
  env.JOBNAME_CLEANED=env.JOB_NAME.replace('/','_')

  
  // configure sudoers file so we can change the PATH variable
  sh '''
    mv /etc/sudoers /etc/sudoers-backup
    grep -v secure_path /etc/sudoers-backup > /etc/sudoers
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
      // Sync upstream changes of submodules
      sh 'sudo -H -E -u msk_jenkins git submodule sync --recursive'
      // Then call update on the submodules
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

def doDependencyArtefacts(ArrayList<String> dependencyList, String label, String buildType, String dependee, ArrayList<String> obtainedArtefacts=[]) {

  // obtain artefacts of dependencies
  script {
    if(dependencyList.size() == 0) return;
    dependencyList.each {
      // skip empty string, seems to come always at end of list
      if(it == '') return;
      
      // provide sensible error message if .jenkinsfile has wrong dependency format somewhere
      if(it.indexOf('/') == -1) {
        currentBuild.result = 'ERROR'
        error("ERROR: Dependency has the wrong format: '${it}'")
      }
      
      // generate job name from dependency name
      def dependency = dependencyToJenkinsProject(it)

      // cleaned version of the job name without slashes, for use in filenames etc.
      def dependency_cleaned = dependency.replace('/','_')
      
      // skip if artefact is already downloaded
      if(obtainedArtefacts.find{it == dependency} == dependency) {
        echo("Dependency '${dependency}' already resolved previously.")
        return;
      }
      echo("Dependency '${dependency}' pulled in by '${dependee}'.")
      obtainedArtefacts.add(dependency)

      // unpack artefact
      def theFile = getArtefactName(true, "install.tgz", label, buildType, it)
      sh """
        tar xf \"${theFile}\" -C / --keep-directory-symlink --use-compress-program="pigz -9 -p32"
      """

      // keep track of dependencies to download - used when dependees need to resolve our dependencies
      def depBuildNo = getBuildNumberFromArtefactFileName(theFile)
      sh """
        touch /scratch/artefact.list
        if [[ "${it}" == *"@"* ]]; then
          echo "${it}" >> /scratch/artefact.list
        else
          echo "${it}@${depBuildNo}" >> /scratch/artefact.list
        fi
      """

      // process dependencies of the dependency we just downloaded
      sh """
        touch /scratch/dependencies.${dependency_cleaned}.list
        cp /scratch/dependencies.${dependency_cleaned}.list ${WORKSPACE}/artefact.list
      """
      myFile = readFile(env.WORKSPACE+"/artefact.list")
      doDependencyArtefacts(new ArrayList<String>(Arrays.asList(myFile.split("\n"))), label, buildType, dependency, obtainedArtefacts)
    }
    
    // fix ownership
    sh """
      chown -R msk_jenkins /scratch
    """
  }

}

/**********************************************************************************************************************/

def doBuilddirArtefact(String label, String buildType) {
  
  // obtain artefacts of dependencies
  script {
    sh """
      rm -rf ${WORKSPACE}/artefacts
    """   
  
    def buildJob = env.BUILD_JOB
    def buildJob_cleaned = buildJob.replace('/','_')
    
    def theFile = getArtefactName(true, "build.tgz", label, buildType)

    // Unpack artefact into the Docker system root (should only write files to /scratch, which is writable by msk_jenkins).
    // Then obtain artefacts of dependencies (from /scratch/artefact.list)
    sh """
      sudo -H -E -u msk_jenkins tar xf \"${theFile}\" -C / --use-compress-program="pigz -9 -p32"

      touch /scratch/artefact.list
      cp /scratch/artefact.list ${WORKSPACE}/artefact.list
    """
    myFile = readFile(env.WORKSPACE+"/artefact.list")
    myFile.split("\n").each {
      if( it != "" ) {
        def dependency = dependencyToJenkinsProject(it)
        def dependency_cleaned = dependency.replace('/','_')

        theFile = getArtefactName(true, "install.tgz", label, buildType, it)
        sh """
          tar xf \"${theFile}\" -C / --use-compress-program="pigz -9 -p32"
        """

      }
    }
  }

  // unpack artefacts of dependencies into the Docker system root
  sh """
    #if ls artefacts/install-*-${label}-${buildType}.tgz 1>/dev/null 2>&1; then
    #  for a in artefacts/install-*-${label}-${buildType}.tgz ; do
    #    tar xf \"\${a}\" -C / --use-compress-program="pigz -9 -p32"
    #  done
    #fi
  """
    
  // fix ownership
  sh """
    chown -R msk_jenkins /scratch
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
mkdir -p /scratch/build-${JOBNAME_CLEANED}
mkdir -p /scratch/install
cd /scratch/build-${JOBNAME_CLEANED}
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
if [ "${DISABLE_TEST}" == "true" ]; then
  BUILD_TESTS_OPT="-DBUILD_TESTS=OFF"
fi

if [ -f "/scratch/source/\${RUN_FROM_SUBDIR}/CMakeLists.txt" ]; then
  cmake /scratch/source/\${RUN_FROM_SUBDIR} -GNinja -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_BUILD_TYPE=${buildType} -DSUPPRESS_AUTO_DOC_BUILD=true \${CMAKE_EXTRA_ARGS} \\\${BUILD_TESTS_OPT}
elif [ -f "/scratch/source/\${RUN_FROM_SUBDIR}/meson.build" ]; then
  meson setup /scratch/source/\${RUN_FROM_SUBDIR} --wrap-mode=nofallback --buildtype=${buildType == "Debug" ? "debug" : "debugoptimized"} --prefix=/export/doocs --libdir 'lib' --includedir 'lib/include' -Db_lundef=false
else
  echo "*********************************************************************"
  echo " Neither CMakeLists.txt nor meson.build found in source directory."
  echo " Don't know what to do. Failing now."
  echo "*********************************************************************"
  exit 1
fi
ninja -v ${MAKEOPTS}
EOF
      cat /scratch/script
      chmod +x /scratch/script
      sudo -H -E -u msk_jenkins /scratch/script
    """
  }
  script {
    // generate and archive artefact from build directory (used e.g. for the analysis job)
    def theFile = getArtefactName(false, "build.tgz", label, buildType)
    sh """
      sudo -H -E -u msk_jenkins tar cf \"${theFile}\" /scratch --use-compress-program="pigz -9 -p32"
    """
  }
}

/**********************************************************************************************************************/

def doRunTests(String label, String buildType) {
  if (env.SKIP_TESTS) {
    currentBuild.result = 'UNSTABLE'
    return
  }

  def buildJob = env.BUILD_JOB
  def buildJob_cleaned = buildJob.replace('/','_')

  // Run the tests via ctest
  // Prefix test names with label and buildType, so we can distinguish them later
  // Copy test results files to the workspace, otherwise they are not available to the xunit plugin
  sh """
    cat > /scratch/script <<EOF
#!/bin/bash
cd /scratch/build-${buildJob_cleaned}
cmake . -DBUILD_TESTS=ON
ninja \${MAKEOPTS}
if [ -z "\${CTESTOPTS}" ]; then
  CTESTOPTS="\${MAKEOPTS}"
fi
for VAR in \${JOB_VARIABLES} \${TEST_VARIABLES}; do
   export \\`eval echo \\\${VAR}\\`
done
ctest --no-compress-output \${CTESTOPTS} -T Test -V || true
sed -i Testing/*/Test.xml -e 's|\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$|\\1${label}.${buildType}.\\2\\3|'
rm -rf "${WORKSPACE}/Testing"
cp -r /scratch/build-${buildJob_cleaned}/Testing "${WORKSPACE}"
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
  def parentJob = env.JOBNAME_CLEANED[0..-10]     // remove "-analysis" from the job name, which is 9 chars long

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
  def parentJob = env.JOBNAME_CLEANED[0..-10]     // remove "-analysis" from the job name, which is 9 chars long

  // Generate coverage report as HTML and also convert it into cobertura XML file
  sh """
    chown msk_jenkins -R /scratch
    cat > /scratch/script <<EOF
#!/bin/bash
cd /scratch/build-${parentJob}
for VAR in \${JOB_VARIABLES} \${TEST_VARIABLES}; do
   export \\`eval echo \\\${VAR}\\`
done
ninja coverage || true
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

def doDeploy(String label, String buildType) {

  // Install, but redirect files into the install directory (instead of installing into the system)
  // Generate tar ball of install directory - this will be the artefact used by our dependents
  def theFile = getArtefactName(false, "install.tgz", label, buildType)
  sh """
    cd /scratch/build-${JOBNAME_CLEANED}
    sudo -H -E -u msk_jenkins bash -c 'DESTDIR=../install ninja install'
  
    cd /scratch/install
    mkdir -p scratch
    if [ -e /scratch/artefact.list ]; then
      cp /scratch/artefact.list scratch/dependencies.${JOBNAME_CLEANED}.list
    fi
    #sudo -H -E -u msk_jenkins tar cf ${WORKSPACE}/install-${JOBNAME_CLEANED}-${label}-${buildType}.tgz . --use-compress-program="pigz -9 -p32"
    sudo -H -E -u msk_jenkins tar cf ${theFile} . --use-compress-program="pigz -9 -p32"
  """
}

/**********************************************************************************************************************/

def doPublishBuild(ArrayList<String> builds) {

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
          error(message: "Could not retreive stashed cobertura results for ${it}")
        }
      }
      
    }
  }

  // publish cobertura result
  cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "*/coverage.xml", conditionalCoverageTargets: '70, 0, 0', failNoReports: false, failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII'
  
}

