/***********************************************************************************************************************

  buildTestDeploy() is called from the .jenkinsfile of each project

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
// The last optional argument is the list of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
def call(ArrayList<String> dependencyList, String gitUrl, ArrayList<String> builds) {

  script {
    helper.setParameters()

    node('Docker') {

      // Reduce list of builds to those builds which exist for all dependencies
      def dependencyListCorrected = [] // version with %2F instead of slashes in project name
      dependencyList.each {
        // skip empty string, seems to come always at end of list
        if(it == '') return;
        
        // provide sensible error message if .jenkinsfile has wrong dependency format somewhere
        if(it.indexOf('/') == -1) {
          currentBuild.result = 'ERROR'
          error("ERROR: Dependency has the wrong format: '${it}'")
        }
        
        // replace slashes in project name with %2F, so we have only one slash separating the folder from the project
        def (folder, project) = it.split('/',2)
        def projectCorrected = project.replace('/','%2F')
        def dependency = "${folder}/${projectCorrected}"
        dependencyListCorrected.add(dependency)
  
        // obtain list of builds for the dependency
        def dependencyCleaned = dependency.replace('/','_')
        myFile = readFile("/home/msk_jenkins/dependency-database/buildnames/${dependencyCleaned}")
        def depBuilds = myFile.split("\n")
        def curBuilds = builds.clone()
        
        // remove all builds from our list of builds which is not present for the dependency
        curBuilds.each {
          if(depBuilds.find { it == dependency } != it) {
            builds.removeAll { it == dependency }
          }
        }
      } // dependencyList.each

      // compute names used below
      def JobNameAsDependency = helper.jekinsProjectToDependency(JOB_NAME)
      def JobNameAsDependencyCleaned = JobNameAsDependency.replace("/","_")

      // publish our list of builds as artefact for our downstream builds
      writeFile file: "/home/msk_jenkins/dependency-database/buildnames/${JobNameAsDependencyCleaned}", text: builds.join("\n")

      // publish our list of builds as artefact for our downstream builds
      writeFile file: "/home/msk_jenkins/dependency-database/jobnames/${JobNameAsDependencyCleaned}", text: JobNameAsDependency
      
      // record our dependencies in central "data base" for explicit dependency triggering
      writeFile file: "/home/msk_jenkins/dependency-database/reverse/${JobNameAsDependencyCleaned}", text:dependencyListCorrected.join("\n")
      def dependencyListJoined = dependencyListCorrected.join(" ").replace("/","_")
      sh """
        for dependency in ${dependencyListJoined}; do
          mkdir -p "/home/msk_jenkins/dependency-database/forward/\${dependency}"
          echo "${JobNameAsDependency}" > "/home/msk_jenkins/dependency-database/forward/\${dependency}/${JobNameAsDependencyCleaned}"
        done
      """
      
      if(helper.BRANCH_UNDER_TEST == JOB_NAME) {
        // first build (i.e. not triggered by upstream project): store list of downstream projects to build
        helper.BUILD_PLAN = helper.generateBuildPlan().flatten()
        helper.DEPENDENCY_BUILD_NUMBERS = [ "${JobNameAsDependency}" : BUILD_NUMBER ]
      }

    } // docker
    
  } // script

  pipeline {
    agent none

    // configure discarding of old builds/artefacts
    options {
      quietPeriod(0)
      buildDiscarder(logRotator(numToKeepStr: '15'))
    }

    stages {
      // apply changes from project-template
      stage('preprocess') {
        steps {
          script {

            node('Docker') {
              if (env.BRANCH_NAME && env.BRANCH_NAME != '') {
                git branch: env.BRANCH_NAME, url: gitUrl
              } else {
                git gitUrl
              }
              sh """
                git reset --hard
                git clean -f -d -x
              """
            }
          }
        }
      } // stage preprocess
      
      stage('build') {
        // Run the build stages for all labels + build types in parallel, each in a separate docker container
        steps {
          script {
            parallel builds.collectEntries { ["${it}" : transformIntoStep(dependencyList, it, gitUrl)] }
          }
        }
      } // stage build

      // run all downstream builds, i.e. also "grand childs" etc.
      stage('downstream-builds') {
        when {
          // downstream builds are run only in the first job, i.e. not when being triggered by an upstream dependency
          expression { return helper.BRANCH_UNDER_TEST == JOB_NAME }
        }
        steps {
          script {
            helper.doDownstreamBuilds()
          }
        }
      } // stage downstream-builds
    } // end stages
    post {
      failure {
        emailext body: '$DEFAULT_CONTENT', recipientProviders: [brokenTestsSuspects(), brokenBuildSuspects(), developers()], subject: '[Jenkins] $DEFAULT_SUBJECT', to: env.MAILTO
      }
      always {
        node('Docker') {
          script {
            helper.doPublishBuild(builds)
          }
        }
      } // end always
    } // end post
  } // end pipeline

}

/**********************************************************************************************************************/

def transformIntoStep(ArrayList<String> dependencyList, String buildName, String gitUrl) {
  // split the build name at the '-'
  def (label, buildType) = buildName.tokenize('-')
  // we need to return a closure here, which is then passed to parallel() for execution
  return {
    stage(buildName) {
      node('Docker') {
        // we need root access inside the container and access to the dummy pcie devices of the host
        def uioFile = sh (returnStdout: true, script: 'readlink /dev/ctkuiodummy')
        def dockerArgs = "-u 0 --privileged --shm-size=1GB --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 --device=/dev/${uioFile} -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy -v /opt/matlab_R2016b:/opt/matlab_R2016b -v /home/msk_jenkins:/home/msk_jenkins"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            helper.doBuildAndDeploy(dependencyList, label, buildType, gitUrl)
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/


