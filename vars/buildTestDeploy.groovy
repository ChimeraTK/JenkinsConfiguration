/***********************************************************************************************************************

  buildTestDeploy() is called from the .jenkinsfile of each project

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
// The last optional argument is the list of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
def call(ArrayList<String> dependencyList, String gitUrl='',
         ArrayList<String> builds=['xenial-Debug',
                                   'xenial-Release',
                                   'xenial-tsan',
                                   'xenial-asan',
                                   'bionic-Debug',
                                   'bionic-Release',
                                   'tumbleweed-Debug',
                                   'tumbleweed-Release']) {

  // lock against other builds depending on this build. Depdencies will only keep the lock shortly before downloading the artefact,
  // so a dependent's build does not prevent us from starting the build but no dependent may start its build from now on.
  lock("build-${env.JOB_NAME}") {

    // only keep builds which exist for all dependencies
    script {
      node('Docker') {
        dependencyList.each {
          if( it != "" ) {
            // wait until dependency is no longer building (to reduce "storm" of builds after core libraries were built)
            lock("build-${it}") {}

            copyArtifacts filter: "builds.txt", fingerprintArtifacts: true, projectName: "${it}", selector: lastSuccessful(), target: "artefacts"
            myFile = readFile(env.WORKSPACE+"/artefacts/builds.txt")
            def depBuilds = myFile.split("\n")
            def curBuilds = builds.clone()
            curBuilds.each {
              def build = it
              if(depBuilds.find { it == build } != it) {
                builds.removeAll { it == build }
              }
            }
          }
        }

        // publish our list of builds as artefact for our downstream builds
        writeFile file: "builds.txt", text: builds.join("\n")
        archiveArtifacts artifacts: "builds.txt", onlyIfSuccessful: false
        
        // publish our list of direct dependencies for our downstream builds
        writeFile file: "dependencyList.txt", text: dependencyList.join("\n")
        archiveArtifacts artifacts: "dependencyList.txt", onlyIfSuccessful: false
      }
    }

    // form comma-separated list of dependencies as needed for the trigger configuration
    def dependencies = dependencyList.join(',')
    if(dependencies == "") {
      dependencies = "Create Docker Images"
    }

    pipeline {
      agent none

      // setup build trigger
      triggers {
        pollSCM('H/5 * * * *')
        upstream(upstreamProjects: dependencies, threshold: hudson.model.Result.UNSTABLE)
      }
      options {
        disableConcurrentBuilds()
        copyArtifactPermission('*')
        buildDiscarder(logRotator(numToKeepStr: '15', artifactNumToKeepStr: '5'))
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
                  git config credential.helper store
                  git remote add project-template "https://github.com/ChimeraTK/project-template" || true
                  git remote set-url origin `echo ${gitUrl} | sed -e 's_http://doocs-git.desy.de/cgit/_git@doocs-git.desy.de:_' -e 's_/\$__'`
                  git remote update
                  git merge --squash --no-edit project-template/master && git commit -m "automatic merge of project-template" && git push --all || true
                """
                // We could also apply the clang-format style here, but this should be discussed first.
                //  find \( -name '*.cc' -o -name '*.cxx' -o -name '*.c' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' -o -name '*.hxx' -o -name '*.hh' \) -exec clang-format-6.0 -style=file -i \{\} \;
                //  git commit -a -m "Automated commit: apply clang-format" && git push --all || true
              }
            }
          }
        }
        stage('build') {
          // Run the build stages for all labels + build types in parallel, each in a separate docker container
          steps {
            script {
              parallel builds.collectEntries { ["${it}" : transformIntoStep(dependencyList, it, gitUrl)] }
            }
          }
        } // end stage build
      } // end stages
      post {
        failure {
          emailext body: '$DEFAULT_CONTENT', recipientProviders: [brokenTestsSuspects(), brokenBuildSuspects(), developers()], subject: '[Jenkins] $DEFAULT_SUBJECT', to: env.MAILTO
          mattermostSend channel: env.JOB_NAME, color: "danger", message: "Build of ${env.JOB_NAME} failed."
          mattermostSend channel: "Jenkins", color: "danger", message: "Build of ${env.JOB_NAME} failed."
        }
        always {
          node('Docker') {
            script {
              helper.doPublishBuildTestDeploy(builds)
            }
          }
          script {
            if (currentBuild?.getPreviousBuild()?.result == 'FAILURE') {
              if (!currentBuild.resultIsWorseOrEqualTo(currentBuild.getPreviousBuild().result)) {
                mattermostSend channel: env.JOB_NAME, color: "good", message: "Build of ${env.JOB_NAME} is good again."
                mattermostSend channel: "Jenkins", color: "good", message: "Build of ${env.JOB_NAME} is good again."
              }
            }
          }
        } // end always
      } // end post
    } // end pipeline
    
  } // end of lock
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
        def dockerArgs = "-u 0 --privileged --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy -v /opt/matlab_R2016b:/opt/matlab_R2016b"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            helper.doBuildTestDeploy(dependencyList, label, buildType, gitUrl)
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/

