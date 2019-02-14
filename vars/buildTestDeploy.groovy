/***********************************************************************************************************************

  buildTestDeploy() is called from the .jenkinsfile of each project

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
def call(ArrayList<String> dependencyList, String gitUrl='') {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'xenial-Debug',
                 'xenial-Release',
                 'bionic-Debug',
                 'bionic-Release',
                 'tumbleweed-Debug',
                 'tumbleweed-Release' ]

  // only keep builds which exist for all dependencies
  script {
    node('Docker') {
      dependencyList.each {
        if( it != "" ) {
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
      pollSCM 'H/5 * * * *'
      upstream dependencies
    }
    options {
      disableConcurrentBuilds()
      copyArtifactPermission('*')
      buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
    }

    stages {
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
        emailext body: '$DEFAULT_CONTENT', recipientProviders: [brokenTestsSuspects(), brokenBuildSuspects(), developers()], subject: '[Jenkins] $DEFAULT_SUBJECT'
      }
      always {
        node('Docker') {
          script {
            helper.doPublishBuildTestDeploy(builds)
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

