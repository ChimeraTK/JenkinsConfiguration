/***********************************************************************************************************************

  buildDoocsLibrary() is called from the pipline script in each project

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
// libraryName is the name of the library incl. the last part of the path in the repository, e.g. "common/clientlib"
def call(String libraryName, ArrayList<String> dependencyList) {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'xenial-Debug',
                 'xenial-Release',
                 'xenial-tsan',
                 'xenial-asan',
                 'bionic-Debug',
                 'bionic-Release' ]

  script {
    node('Docker') {
      // publish our list of builds as artefact for our downstream builds
      writeFile file: "builds.txt", text: builds.join("\n")
      archiveArtifacts artifacts: "builds.txt", onlyIfSuccessful: false
 
      // publish our list of direct dependencies for our downstream builds
      writeFile file: "dependencyList.txt", text: dependencyList.join("\n")
      archiveArtifacts artifacts: "dependencyList.txt", onlyIfSuccessful: false    }
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
        def gitUrl = "http://doocs-git.desy.de/cgit/doocs/library/${libraryName}.git"
        if (env.BRANCH_NAME && env.BRANCH_NAME != '') {
          git branch: env.BRANCH_NAME, url: gitUrl
        } else {
          git gitUrl
        }
        // we need root access inside the container
        def dockerArgs = "-u 0 --privileged"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            sh '''
              mkdir /scratch
              mkdir -p /export/doocs/library/common
            '''
            helper.doDependencyArtefacts(dependencyList, label, buildType)

            sh """
              mkdir -p /export/doocs/library/${libraryName}
              cd /export/doocs/library/${libraryName}
              chown -R msk_jenkins /export/doocs
              sudo -H -u msk_jenkins git clone ${gitUrl} .
              if [ -f meson.build ]; then
                mkdir -p build
                chown -R msk_jenkins /export
                ls -alR /export
                # set meson build type
                if [ "${buildType}" == "Debug" ]; then
                  buildType="debug"
                else
                  buildType="debugoptimized"
                fi
                #if [ "${buildType}" == "tsan" ]; then
                #  export CC="clang-6.0"
                #  export CXX="clang++-6.0"
                #  export CFLAGS="-fsanitize=thread"
                #  export CXXFLAGS="\$CFLAGS"
                #  export LDFLAGS="\$CFLAGS"
                #elif [ "${buildType}" == "asan" ]; then
                #  export CC="clang-6.0"
                #  export CXX="clang++-6.0"
                #  export CFLAGS="-fsanitize=address -fsanitize=undefined -fsanitize=leak"
                #  export CXXFLAGS="\$CFLAGS"
                #  export LDFLAGS="\$CFLAGS"
                #fi
                export LSAN_OPTIONS=verbosity=1:log_threads=1
                export PKG_CONFIG_PATH=/export/doocs/lib/pkgconfig
                # TEMPORARY FIX: remove libtirpc dependency. Somehow only SUN RPC works for us. Needs investigation.
                find /export/doocs/lib/pkgconfig -name *.pc --exec sed -i \{\} -e 's/Requires.private: libtirpc//' \;
                sudo -E -H -u msk_jenkins meson build --buildtype=\${buildType} --prefix=/export/doocs --libdir 'lib' --includedir 'lib/include' -Db_lundef=false
                sudo -E -H -u msk_jenkins ninja -C build
                find /export > /export.list.before
                sudo -E -H -u msk_jenkins ninja -C build install
                find /export > /export.list.after
              else
                source /export/doocs/doocsarch.env
                export LSAN_OPTIONS=verbosity=1:log_threads=1
                if [ -z "\${MAKEOPTS}" ]; then
                  make -j8
                else
                  make "\${MAKEOPTS}"
                fi   
                find /export > /export.list.before
                make install
                find /export > /export.list.after
              fi
              cd "$WORKSPACE"
              diff /export.list.before /export.list.after | grep "^> " | sed -e 's/^> //' > export.list.installed
              touch mv /scratch/artefact.list
              mv /scratch/artefact.list /scratch/dependencies.${JOB_NAME}.list
              echo /scratch/dependencies.${JOB_NAME}.list >> export.list.installed
              sudo -H -u msk_jenkins tar zcf install-${JOB_NAME}-${label}-${buildType}.tgz --files-from export.list.installed
            """
            archiveArtifacts artifacts: "install-${JOB_NAME}-${label}-${buildType}.tgz", onlyIfSuccessful: false
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/


