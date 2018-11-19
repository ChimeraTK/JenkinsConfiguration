/***********************************************************************************************************************

  buildDoocsLibrary() is called from the pipline script in each project

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
// libraryName is the name of the library incl. the last part of the path in the repository, e.g. "common/clientlib"
def call(String libraryName, ArrayList<String> dependencyList) {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'xenial-Debug',
                 'xenial-Release',
                 'bionic-Debug',
                 'bionic-Release' ]

  // publish our list of builds as artefact for our downstream builds
  script {
    node('Docker') {
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

    stages {
      stage('build') {
        // Run the build stages for all labels + build types in parallel, each in a separate docker container
        steps {
          script {
            parallel builds.collectEntries { ["${it}" : transformIntoStep(libraryName, dependencyList, it)] }
          }
        }
      } // end stage build
    } // end stages
  } // end pipeline

}

/**********************************************************************************************************************/

def transformIntoStep(String libraryName, ArrayList<String> dependencyList, String buildName) {
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
            sh '''
              mkdir /scratch
              mkdir -p /export/doocs/library/common
            '''
            helper.doDependencyArtefacts(dependencyList, label, buildType)

            sh """
              source /export/doocs/doocsarch.env
              mkdir -p /export/doocs/library/${libraryName}
              cd /export/doocs/library/${libraryName}
              chown -R msk_jenkins /export/doocs
              sudo -E -u msk_jenkins git clone http://doocs-git.desy.de/cgit/doocs/library/${libraryName}.git .
              make -j8
              find /export > /export.list.before
              make install
              find /export > /export.list.after
              cd "$WORKSPACE"
              diff /export.list.before /export.list.after | grep "^> " | sed -e 's/^> //' > export.list.installed
              mv /scratch/artefact.list /scratch/dependencies.${JOB_NAME}.list
              echo /scratch/dependencies.${JOB_NAME}.list >> export.list.installed
              sudo -u msk_jenkins tar zcf install-${JOB_NAME}-${label}-${buildType}.tgz --files-from export.list.installed
            """
            archiveArtifacts artifacts: "install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/


