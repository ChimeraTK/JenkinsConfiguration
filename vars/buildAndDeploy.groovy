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

            // if this job has no dependency, make sure it gets triggered when docker images are renewed
            if(dependencyList.isEmpty()) {
              properties([pipelineTriggers([upstream('Create Docker Images')])])
            }

            node('Docker') {
              if (env.BRANCH_NAME && env.BRANCH_NAME != '') {
                git branch: env.BRANCH_NAME, url: gitUrl
              } else {
                git gitUrl
              }
              sh """
                git reset --hard
                git clean -f -d -x
                #git config credential.helper store
                #git remote add project-template "https://github.com/ChimeraTK/project-template" || true
                #git remote set-url origin `echo ${gitUrl} | sed -e 's_http://doocs-git.desy.de/cgit/_git@doocs-git.desy.de:_' -e 's_/\$__'`
                #git remote update
                #git merge -X theirs --no-edit project-template/master && \
                #git push --all || \
                #true
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

      // run all downstream builds, i.e. also "grand childs" etc.
      // Implementation note:
      //  - create one parallel step per downstream project (including the test of the main job)
      //  - each parallel step initially waits until all its dependencies have been built
      //  - signalling between parallel steps is realised through a Condition object to wake up waiting jobs and
      //    a build status flag
      //  - FIXME: Need to clarify whether we need to use locks or so to protect the maps!
      stage('downstream-builds') {
        when {
          // downstream builds are run only in the first job, i.e. not when being triggered by an upstream dependency
          expression { return helper.BRANCH_UNDER_TEST == JOB_NAME }
        }
        steps {
          script {

            // buildDone: map of condition variables to signal when build terminates
            def buildDone = [:]
            // buildStatus: map of build statuses (true = ok, false = failed)
            def buildStatus = [:]
            helper.BUILD_PLAN.each {
              buildDone[it] = createCondition()
            }
            buildDone[helper.jekinsProjectToDependency(JOB_NAME)] = createCondition()
            buildStatus[helper.jekinsProjectToDependency(JOB_NAME)] = true

            // add special build name for the test to be run
            def buildList = helper.BUILD_PLAN + "TESTS"
            
            // execute parallel step for all builds
            parallel buildList.collectEntries { ["${it}" : {
              if(it == "TESTS") {
                // build the test. Result is always propagated.
                build(job: JOB_NAME.replace("/${env.JOB_TYPE}/", "/${env.JOB_TYPE}-testing/"),
                      propagate: true, wait: true, parameters: [
                        string(name: 'BRANCH_UNDER_TEST', value: helper.BRANCH_UNDER_TEST),
                        string(name: 'BUILD_PLAN', value: groovy.json.JsonOutput.toJson(helper.BUILD_PLAN)),
                        string(name: 'DEPENDENCY_BUILD_NUMBERS',
                               value: groovy.json.JsonOutput.toJson(helper.DEPENDENCY_BUILD_NUMBERS))
                ])
                return
              }
            
              // build downstream project
              def theJob = helper.dependencyToJenkinsProject(it, true)
              
              // signal builds downstream of this build when finished (they are all waiting in parallel)
              signalAll(buildDone[it]) {

                // wait until all dependencies which are also build here (in parallel) are done
                def myDeps
                node {
                  myDeps = helper.gatherDependenciesDeep([it])
                }
                def failedDeps = false
                myDeps.each { dep ->
                  // myDeps contains the job itself -> ignore it
                  if(dep == it) return
                  // ignore dependencies which are not build within this job
                  if(!buildDone.containsKey(dep)) return
                  // if build status not yet set, wait for notification
                  // Attention: There is a potential race condition if the notification happens between the check of the
                  // build status and the call to awaitCondition()! Unclear how to solve this. For now we just add a
                  // sleep between setting the build status and sending the notification below.
                  if(!buildStatus.containsKey(dep)) {
                    echo("Waiting for depepdency ${dep}...")
                    awaitCondition(buildDone[dep])
                  }
                  if(!buildStatus[dep]) {
                    echo("Depepdency ${dep} has failed, not triggering downstream build...")
                    failedDeps = true
                  }
                }
                if(failedDeps) {
                  echo("Not proceeding with downstream build due to failed dependencies.")
                  return
                }

                // trigger the build and wait until done
                // Note: propagate=true would abort+fail even if downstream build result is unstable. Also in case of
                // a failure we first need to set the buildStatus before failing...
                // In any case, the build result is only propagated for branches builds.
                def r = build(job: theJob, propagate: false, wait: true, parameters: [
                  string(name: 'BRANCH_UNDER_TEST', value: helper.BRANCH_UNDER_TEST),
                  string(name: 'BUILD_PLAN', value: groovy.json.JsonOutput.toJson(helper.BUILD_PLAN)),
                  string(name: 'DEPENDENCY_BUILD_NUMBERS',
                         value: groovy.json.JsonOutput.toJson(helper.DEPENDENCY_BUILD_NUMBERS))
                ])

                def number = r.getNumber()
                helper.DEPENDENCY_BUILD_NUMBERS[it] = number

                def result =  hudson.model.Result.fromString(r.result)
                
                if(result == Result.SUCCESS) {
                  buildStatus[it] = true
                  sleep(5) // mitigate race condition, see above
                  echo("Build result of ${it} is SUCCESS.")
                }
                else if(result == Result.UNSTABLE) {
                  buildStatus[it] = true
                  sleep(5) // mitigate race condition, see above
                  if(env.JOB_TYPE == "branches")  {
                    unstable(message: "Build result of ${it} is UNSTABLE.")
                  }
                  else {
                    echo("Build result of ${it} is UNSTABLE.")
                  }
                }
                else {
                  buildStatus[it] = false
                  sleep(5) // mitigate race condition, see above
                  if(env.JOB_TYPE == "branches")  {
                    error(message: "Build result of ${it} is FAILURE (or ABORTED etc.).")
                  }
                  else {
                    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                      error(message: "Build result of ${it} is FAILURE (or ABORTED etc.).")
                    }
                  }
                }

              } // <-- signal downstream projects waiting for this build

              // trigger the test and wait until done
              // propagate=true is ok here, since we do not do anything further downstream in this parallel stage.
              // Again, the build result is only propagated for branches builds.
              build(job: theJob.replace("/${env.JOB_TYPE}/", "/${env.JOB_TYPE}-testing/"),
                    propagate: (env.JOB_TYPE == "branches"), wait: true, parameters: [
                      string(name: 'BRANCH_UNDER_TEST', value: helper.BRANCH_UNDER_TEST),
                      string(name: 'BUILD_PLAN', value: groovy.json.JsonOutput.toJson(helper.BUILD_PLAN)),
                      string(name: 'DEPENDENCY_BUILD_NUMBERS',
                             value: groovy.json.JsonOutput.toJson(helper.DEPENDENCY_BUILD_NUMBERS))
              ])

            }] }
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


