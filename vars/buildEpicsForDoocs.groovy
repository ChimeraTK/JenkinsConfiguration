/***********************************************************************************************************************

  buildEpicsForDoocs() is called from pipeline script of the DOOCS_epics job

***********************************************************************************************************************/

// This is the function called from the pipeline script
def call() {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'xenial-Debug',
                 'xenial-Release',
                 'xenial-tsan',
                 'xenial-asan',
                 'bionic-Debug',
                 'bionic-Release' ]

  pipeline {
    agent none
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
        // we need root access inside the container
        def dockerArgs = "-u 0"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            sh """
              env
              pwd
              mkdir -p /scratch/epics
              chown msk_jenkins -R /scratch
              cd /scratch/epics
              VERSION=3.14.12.6
              sudo -E -H -u msk_jenkins wget https://epics.anl.gov/download/base/baseR\${VERSION}.tar.gz
              sudo -H -u msk_jenkins tar xf baseR\${VERSION}.tar.gz
              cd base-\${VERSION}
              echo "INSTALL_LOCATION=/export/epics" >> configure/CONFIG_SITE
              export LC_ALL=en_US.UTF-8
              mkdir -p /export/epics
              chown msk_jenkins /export/epics
              sudo -H -u msk_jenkins make all
              # fix include directory as expected by DOOCS
              cd /export/epics/include
              ln -sfn `pwd` epics
              # fix library directory as expected by DOOCS
              cd /export/epics/lib
              ln -sfn linux-x86_64/* .
              mkdir -p /scratch
              touch /scratch/dependencies.${JOB_NAME}.list
              sudo -H -u msk_jenkins tar zcf "$WORKSPACE/install-${JOB_NAME}-${label}-${buildType}.tgz" /export /scratch
            """
            archiveArtifacts artifacts: "install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/


