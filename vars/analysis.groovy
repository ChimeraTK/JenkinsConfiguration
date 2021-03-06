/***********************************************************************************************************************

  analysis() is called from the .jenkinsfile of each project
  
  Note: set "env.valgrindExcludes" to a space-separated list of test names to be excluded from valgrind before calling!

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
def call() {
  def builds = []
  def parentJob = env.JOB_NAME[0..-10]     // remove "-analysis" from the job name, which is 9 chars long

  // Run for all -Debug builds of the main job
  script {
    node('Docker') {
      copyArtifacts filter: "builds.txt", fingerprintArtifacts: true, projectName: parentJob, selector: lastSuccessful(), target: "artefacts"
      myFile = readFile(env.WORKSPACE+"/artefacts/builds.txt")
      builds = myFile.split("\n").toList()
      def builds_temp = builds.clone()
      builds_temp.each {
        if(!it.endsWith("-Debug") && !it.endsWith("-asan") && !it.endsWith("-tsan")) {
          def build = it
          builds.removeAll { it == build }
        }
      }

      // Update JenkinsConfiguration to have the latest valgrind suppressions
      sh '''
        cd /home/msk_jenkins/JenkinsConfiguration
        git pull || true
      '''
    }
  }

  pipeline {
    agent none
    
    // setup build trigger etc.
    triggers {
      upstream(upstreamProjects: parentJob, threshold: hudson.model.Result.UNSTABLE)
    }
    options {
      disableConcurrentBuilds()
      copyArtifactPermission('*')
      buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '1'))
    }
  
    stages {
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
        def dockerArgs = "-u 0 --privileged --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy -v /home/msk_jenkins/JenkinsConfiguration:/home/msk_jenkins/JenkinsConfiguration"
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

