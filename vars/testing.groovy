/***********************************************************************************************************************

  test() is called from autojob for fasttrack-test jobs after the corresponding fasttrack job has completed

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
def call() {
  
  ArrayList<String> builds

  script {
    helper.setParameters()
    env.BUILD_JOB = helper.dependencyToJenkinsProject("${env.ORGANISATION}/${env.PROJECT}")
  }

  pipeline {
    agent none
    
    // configure artefact permissions and discarding of old builds/artefacts
    options {
      quietPeriod(0)
      copyArtifactPermission('*')
      buildDiscarder(logRotator(numToKeepStr: '15', artifactNumToKeepStr: '2'))
    }

    stages {
      stage('prepare') {
        steps {
          script {
            node('Docker') {
              // fetch list of build types
              def JobNameAsDependency = helper.jekinsProjectToDependency(JOB_NAME)
              def JobNameAsDependencyCleaned = JobNameAsDependency.replace("/","_")
              def myFile = readFile("/home/msk_jenkins/dependency-database/buildnames/${JobNameAsDependencyCleaned}")
              builds = myFile.split("\n")
            }
          }
        }
      }
      stage('test') {
        // Run the build stages for all labels + build types in parallel, each in a separate docker container
        steps {
          script {
            parallel builds.collectEntries { ["${it}" : transformIntoStep(it)] }
          }
        }
      } // stage build
    } // end stages
    post {
      failure {
        emailext body: '$DEFAULT_CONTENT', recipientProviders: [brokenTestsSuspects(), brokenBuildSuspects(), developers()], subject: '[Jenkins] $DEFAULT_SUBJECT', to: env.MAILTO
        //mattermostSend channel: env.JOB_NAME, color: "danger", message: "Build of ${env.JOB_NAME} failed."
        //mattermostSend channel: "Jenkins", color: "danger", message: "Build of ${env.JOB_NAME} failed."
      }
      always {
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

def transformIntoStep(String buildName) {
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
            helper.doTesting(label, buildType)
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/


