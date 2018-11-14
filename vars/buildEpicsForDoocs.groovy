/***********************************************************************************************************************

  buildEpicsForDoocs() is called from pipeline script of the DOOCS_epics job

***********************************************************************************************************************/

// This is the function called from the pipeline script
def call() {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'xenial-Debug',
                 'xenial-Release',
                 'bionic-Debug',
                 'bionic-Release',
                 'tumbleweed-Debug',
                 'tumbleweed-Release' ]

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
        // we need root access inside the container and access to the dummy pcie devices of the host
        def dockerArgs = "-u 0 --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            sh """
              VERSION=3.14.12.6
              sudo -E -u msk_jenkins wget https://epics.anl.gov/download/base/baseR\${VERSION}.tar.gz
              sudo -u msk_jenkins tar xf baseR\${VERSION}.tar.gz
              cd base-\${VERSION}
              echo "INSTALL_LOCATION=/export/epics" >> configure/CONFIG_SITE
              export LC_ALL=en_US.UTF-8
              mkdir -p /export/epics
              chown msk_jenkins /export/epics
              sudo -u msk_jenkins make all
              # fix include directory as expected by DOOCS
              cd /export/epics/include
              ln -sfn `pwd` epics
              # fix library directory as expected by DOOCS
              cd /export/epics/lib
              ln -sfn linux-x86_64/* .
              mkdir -p /scratch
              touch /scratch/dependencies.${JOB_NAME}.list
              sudo -u msk_jenkins tar zcf "$WORKSPACE/install-${JOB_NAME}-${label}-${buildType}.tgz" /export /scratch
            """
            archiveArtifacts artifacts: "install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/


