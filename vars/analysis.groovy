/***********************************************************************************************************************

  analysis() is called from the .jenkinsfile of each project
  
  Note: set "env.valgrindExcludes" to a space-separated list of test names to be excluded from valgrind before calling!

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
    
    // setup build trigger etc.
    options {
      quietPeriod(0)
      buildDiscarder(logRotator(numToKeepStr: '10'))
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

              // Update JenkinsConfiguration to have the latest valgrind suppressions
              sh '''
                cd /home/msk_jenkins/JenkinsConfiguration
                git pull || true
              '''
            }
          }
        }
      }
      stage('build') {
        // Run the build stages for all labels + build types in parallel, each in a separate docker container
        steps {
          script {
            parallel builds.collectEntries { ["${it}" : transformIntoStep(it)] }
          }
        }
      } // end stage build
    } // end stages
    post {
      always {
        node('Docker') {
          script {
            helper.doPublishAnalysis(builds)
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
        def dockerArgs = "-u 0 --privileged --shm-size=1GB --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy -v /opt/matlab_R2016b:/opt/matlab_R2016b -v /home/msk_jenkins:/home/msk_jenkins"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            helper.doAnalysis(label, buildType)
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/

