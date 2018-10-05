/***********************************************************************************************************************

  buildTestDeploy() is called from the .jenkinsfile of each project
  
  Note: set "env.valgrindExcludes" to a space-separated list of test names to be excluded from valgrind before calling!

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
def call(ArrayList<String> dependencyList) {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'xenial-Debug',
                 'bionic-Debug',
                 'tumbleweed-Debug' ]

  pipeline {
    agent none
    stages {
      stage('build') {
        // Run the build stages for all labels + build types in parallel, each in a separate docker container
        steps {
          script {
            parallel builds.collectEntries { ["${it}" : transformIntoStep(dependencyList, it)] }
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

def transformIntoStep(ArrayList<String> dependencyList, String buildName) {
  // split the build name at the '-'
  def (label, buildType) = buildName.tokenize('-')
  // we need to return a closure here, which is then passed to parallel() for execution
  return {
    stage(buildName) {
      node('Docker') {
        // we need root access inside the container and access to the dummy pcie devices of the host
        def dockerArgs = "-u 0 --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            helper.doAnalysis(dependencyList, label, buildType)
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/

