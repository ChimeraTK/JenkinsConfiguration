/***********************************************************************************************************************

  buildDoocsTargetConfig() is called from pipeline script of the DOOCS_target-configuration job

***********************************************************************************************************************/

// This is the function called from the pipeline script
def call() {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'xenial-Debug',
                 'xenial-Release',
                 'xenial-tsan',
                 'xenial-asan',
                 'bionic-Debug',
                 'bionic-Release',
                 'focal-Debug',
                 'focal-Release']

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
              if [ "${label}" == "focal" ]; then
                DOOCSARCH=Ubuntu-20.04-x86_64
              fi
              echo "export DOOCSARCH=\${DOOCSARCH}" > /export/doocs/doocsarch.env
              sudo -H -u msk_jenkins git clone http://doocs-git.desy.de/cgit/doocs/\${DOOCSARCH}.git
              mkdir -p /export/doocs/\${DOOCSARCH}/lib/pkgconfig
              touch /export/doocs/\${DOOCSARCH}/lib/pkgconfig/.keep
              cd \${DOOCSARCH}
              sed -i CONFIG -e 's|^EPICS[[:space:]]*=.*\$|EPICS = '/export/epics'|'
              if [ "${buildType}" == "tsan" ]; then
                sed -i CONFIG -e 's/%.o:/# OVERRIDES\\n\\nCC = clang-8\\nCXX = clang++-8\\nCFLAGS += -fsanitize=thread\\nCXXFLAGS += -fsanitize=thread\\nLDFLAGS += -fsanitize=thread\\n\\n%.o:/'
                #sed -i CONFIG -e 's/%.o:/# OVERRIDES\\n\\nCFLAGS += -fsanitize=thread\\nCXXFLAGS += -fsanitize=thread\\nLDFLAGS += -fsanitize=thread\\n\\n%.o:/'
              elif [ "${buildType}" == "asan" ]; then
                sed -i CONFIG -e 's/%.o:/# OVERRIDES\\n\\nCC = clang-8\\nCXX = clang++-8\\nCFLAGS += -fsanitize=address -fsanitize=undefined -fsanitize=leak\\nCXXFLAGS += -fsanitize=address -fsanitize=undefined -fsanitize=leak\\nLDFLAGS += -fsanitize=address -fsanitize=undefined -fsanitize=leak\\n\\n%.o:/'
                #sed -i CONFIG -e 's/%.o:/# OVERRIDES\\n\\nCFLAGS += -fsanitize=address -fsanitize=undefined -fsanitize=leak\\nCXXFLAGS += -fsanitize=address -fsanitize=undefined -fsanitize=leak\\nLDFLAGS += -fsanitize=address -fsanitize=undefined -fsanitize=leak\\n\\n%.o:/'
              fi
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


