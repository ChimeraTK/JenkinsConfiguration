/***********************************************************************************************************************

  buildTestDeploy() is called from the .jenkinsfile of each project

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
// The last optional argument is the list of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
def call(ArrayList<String> dependencyList, String gitUrl, ArrayList<String> builds) {

  def dependencyJobList = new ArrayList<String>()
  
  // Temporary work around for branches builds: Since build artefacts would be used from wrong builds if multiple
  // branches are build/tested at the same time, we block here until no other branch is currently build/tested.
  // The resourceToLock initially contains a unique name so the lock will not be effective by default. Only if this
  // build is the first branches build the name will be changed below into 'branches-build', such that no concurrent
  // build can occur.
  def resourceToLock = "not-a-lock-${JOB_NAME}@${BUILD_NUMBER}"

  script {

    // if branches job type, add parameter BRANCH_UNDER_TEST
    if(env.JOB_TYPE == "branches") {
      properties([
        parameters([
          string(name: 'BRANCH_UNDER_TEST', description: 'Jenkins project name for the branch to be tested', defaultValue: JOB_NAME),
          string(name: 'BUILD_PLAN', description: 'JSON object with plan of the build', defaultValue: '[]'),
        ])
      ])

      helper.BUILD_PLAN = new groovy.json.JsonSlurper().parseText(params.BUILD_PLAN)
     
      println("helper.BUILD_PLAN = ${helper.BUILD_PLAN}")
      println("params.BRANCH_UNDER_TEST = ${params.BRANCH_UNDER_TEST}")
  
      if(params.BRANCH_UNDER_TEST == JOB_NAME) {
        println("This is the main job in a branches build. Exclude concurrent builds!")
        resourceToLock = "branches-build"
      }

    }
    
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
        
        // generate job name from dependency name
        def dependencyProjectName = helper.dependencyToJenkinsProject(dependency)
        dependencyJobList.add(dependencyProjectName)
  
        // obtain list of builds for the dependency
        copyArtifacts filter: "builds.txt", fingerprintArtifacts: true, projectName: dependencyProjectName, selector: lastSuccessful(), target: "artefacts"
        myFile = readFile(env.WORKSPACE+"/artefacts/builds.txt")
        def depBuilds = myFile.split("\n")
        def curBuilds = builds.clone()
        
        // remove all builds from our list of builds which is not present for the dependency
        curBuilds.each {
          if(depBuilds.find { it == dependency } != it) {
            builds.removeAll { it == dependency }
          }
        }
      } // dependencyList.each

      // publish our list of builds as artefact for our downstream builds
      writeFile file: "builds.txt", text: builds.join("\n")
      archiveArtifacts artifacts: "builds.txt", onlyIfSuccessful: false
      
      // publish our list of direct dependencies for our downstream builds
      writeFile file: "dependencyList.txt", text:dependencyListCorrected.join("\n")
      archiveArtifacts artifacts: "dependencyList.txt", onlyIfSuccessful: false
      
      // record our dependencies in central "data base" for explicit dependency triggering
      def dependencyListJoined = dependencyListCorrected.join(" ").replace("/","_")
      def JobNameAsDependency = helper.jekinsProjectToDependency(JOB_NAME)
      def JobNameAsDependencyCleaned = JobNameAsDependency.replace("/","_")
      sh """
        for dependency in ${dependencyListJoined}; do
          mkdir -p "/home/msk_jenkins/dependency-database/forward/\${dependency}"
          echo "${JobNameAsDependency}" > "/home/msk_jenkins/dependency-database/forward/\${dependency}/${JobNameAsDependencyCleaned}"
        done
        cp "${WORKSPACE}/dependencyList.txt" "/home/msk_jenkins/dependency-database/reverse/${JobNameAsDependencyCleaned}"
      """
      
      println("============ HIER 1")

      if(env.JOB_TYPE == "branches" && params.BRANCH_UNDER_TEST == JOB_NAME) {
        println("============ HIER 2")
        // first branches-typed build: create build plan
        helper.BUILD_PLAN = helper.generateBuildPlan()
        println("helper.BUILD_PLAN = ${helper.BUILD_PLAN}")
      }

    } // docker
    
  } // script

  // form comma-separated list of dependencies as needed for the trigger configuration
  def dependencies = dependencyJobList.join(',')
  if(dependencies == "") {
    dependencies = "Create Docker Images"
  }
  
  lock(resource: resourceToLock) {

    pipeline {
      agent none

      // setup build trigger
      // Note: do not trigger automatically by dependencies, since this is implemented explicitly to have more control.
      // The dependencies are tracked above in the scripts section in a central "database" and used to trigger downstream
      // build jobs after the build.
      triggers {
        pollSCM('* * * * *')
      }
      options {
        //disableConcurrentBuilds()
        quietPeriod(0)
        copyArtifactPermission('*')
        buildDiscarder(logRotator(numToKeepStr: '15', artifactNumToKeepStr: '2'))
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
                  git config credential.helper store
                  git remote add project-template "https://github.com/ChimeraTK/project-template" || true
                  git remote set-url origin `echo ${gitUrl} | sed -e 's_http://doocs-git.desy.de/cgit/_git@doocs-git.desy.de:_' -e 's_/\$__'`
                  git remote update
                  git merge -X theirs --no-edit project-template/master && \
                  git push --all || \
                  true
                """
                // We could also apply the clang-format style here, but this should be discussed first.
                //  find \( -name '*.cc' -o -name '*.cxx' -o -name '*.c' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' -o -name '*.hxx' -o -name '*.hh' \) -exec clang-format-6.0 -style=file -i \{\} \;
                //  git commit -a -m "Automated commit: apply clang-format" && git push --all || true
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
        
        stage('downstream-builds') {
          when {
            expression { return params.BRANCH_UNDER_TEST == JOB_NAME }
          }
          steps {
            script {
              helper.BUILD_PLAN.each { buildGroup ->
                parallel buildGroup.collectEntries { ["${it}" : {
                  def theJob = helper.dependencyToJenkinsProject(it, true)
                  
                  def r = build(job: theJob, propagate: false, wait: true, parameters: [
                    string(name: 'BRANCH_UNDER_TEST', value: params.BRANCH_UNDER_TEST),
                    string(name: 'BUILD_PLAN', value: groovy.json.JsonOutput.toJson(helper.BUILD_PLAN))
                  ])
                  currentBuild.result = hudson.model.Result.combine(hudson.model.Result.fromString(currentBuild.currentResult), hudson.model.Result.fromString(r.result))

                  build(job: theJob.replace("/branches/", "/branches-testing/"), propagate: true, wait: false, parameters: [
                    string(name: 'BRANCH_UNDER_TEST', value: params.BRANCH_UNDER_TEST),
                    string(name: 'BUILD_PLAN', value: groovy.json.JsonOutput.toJson(helper.BUILD_PLAN))
                  ])
                }] }
              }
            }
          }
        } // stage downstream-builds
      } // end stages
      post {
        failure {
          emailext body: '$DEFAULT_CONTENT', recipientProviders: [brokenTestsSuspects(), brokenBuildSuspects(), developers()], subject: '[Jenkins] $DEFAULT_SUBJECT', to: env.MAILTO
          //mattermostSend channel: env.JOB_NAME, color: "danger", message: "Build of ${env.JOB_NAME} failed."
          //mattermostSend channel: "Jenkins", color: "danger", message: "Build of ${env.JOB_NAME} failed."
        }
        always {
          node('Docker') {
            script {
              helper.doPublishBuild(builds)
            }
          }
          script {
            if (currentBuild?.getPreviousBuild()?.result == 'FAILURE') {
              if (!currentBuild.resultIsWorseOrEqualTo(currentBuild.getPreviousBuild().result)) {
                //mattermostSend channel: env.JOB_NAME, color: "good", message: "Build of ${env.JOB_NAME} is good again."
                //mattermostSend channel: "Jenkins", color: "good", message: "Build of ${env.JOB_NAME} is good again."
              }
            }
          }
        } // end always
      } // end post
    } // end pipeline
  
  } // end lock
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
        def dockerArgs = "-u 0 --privileged --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy -v /opt/matlab_R2016b:/opt/matlab_R2016b -v /home/msk_jenkins:/home/msk_jenkins"
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


