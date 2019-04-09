/***********************************************************************************************************************

  buildDoocsTargetConfig() is called from pipeline script of the DOOCS_target-configuration job

***********************************************************************************************************************/

// This is the function called from the pipeline script
def call() {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'xenial-Debug',
                 'xenial-Release',
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
        // we need root access inside the container and access to the dummy pcie devices of the host
        def dockerArgs = "-u 0 --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            sh """
              echo $label $buildType
              pwd
              ls
              mkdir -p /export/doocs/library
              cd /export/doocs
              chown -R msk_jenkins /export
              DOOCSARCH=Ubuntu-16.04-x86_64
              if [ "${label}" == "bionic" ]; then
                DOOCSARCH=Ubuntu-18.04-x86_64
              fi
              echo "export DOOCSARCH=\${DOOCSARCH}" > /export/doocs/doocsarch.env
              sudo -H -u msk_jenkins git clone http://doocs-git.desy.de/cgit/doocs/\${DOOCSARCH}.git
              mkdir -p /export/doocs/${DOOCSARCH}/lib/pkgconfig
              touch /export/doocs  /${DOOCSARCH}/lib/pkgconfig/.keep
              cd \${DOOCSARCH}
              sed -i CONFIG -e 's|^EPICS[[:space:]]*=.*\$|EPICS = '/export/epics'|'
              mkdir -p /scratch
              echo "DOOCS_epics" > /scratch/dependencies.${JOB_NAME}.list
              chown -R msk_jenkins /export
              chown -R msk_jenkins /scratch
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


