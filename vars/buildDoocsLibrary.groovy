/***********************************************************************************************************************

  buildDoocsLibrary() is called from the pipline script in each project

***********************************************************************************************************************/

// This is the function called from the .jenkinsfile
// libraryName is the name of the library incl. the last part of the path in the repository, e.g. "common/clientlib"
def call(String libraryName, ArrayList<String> dependencyList) {

  // List of builds to be run. Format must be "<docker_image_name>-<cmake_build_type>"
  def builds = [ 'focal-Debug',
                 'focal-Release',
                 'focal-tsan',
                 'focal-asan'
                 ]

  def dependencies

  script {
    node('Docker') {
      def JobNameAsDependency = JOB_NAME
      def JobNameAsDependencyCleaned = JobNameAsDependency.replace("/","_")

      // publish our list of builds as artefact for our downstream builds
      writeFile file: "/home/msk_jenkins/dependency-database/buildnames/${JobNameAsDependencyCleaned}", text: builds.join("\n")
 
      // publish our list of direct dependencies for our downstream builds
      writeFile file: "/home/msk_jenkins/dependency-database/reverse/${JobNameAsDependencyCleaned}", text: dependencyList.join("\n")

      // form comma-separated list of dependencies as needed for the trigger configuration
      dependencies = dependencyList.join(',')
      if(dependencies == "") {
        dependencies = "Create Docker Images"
      }
      
      // record our dependencies in central "data base" for explicit dependency triggering
      def dependencyListJoined = dependencyList.join(" ").replace("/","_")
      sh """
        for dependency in ${dependencyListJoined}; do
          mkdir -p "/home/msk_jenkins/dependency-database/forward/\${dependency}"
          echo "${JobNameAsDependency}" > "/home/msk_jenkins/dependency-database/forward/\${dependency}/${JobNameAsDependencyCleaned}"
        done
      """
      
      def (organisation, project) = env.JOB_NAME.tokenize('/')
      env.ORGANISATION = organisation
      env.JOB_TYPE = "fasttrack"
      env.PROJECT = project
      env.BRANCH = "master"
      helper.setParameters()

    }
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
      //quietPeriod(180)
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
  def JOBNAME_CLEANED=env.JOB_NAME.replace('/','_')

  // split the build name at the '-'
  def (label, buildType) = buildName.tokenize('-')
  // we need to return a closure here, which is then passed to parallel() for execution
  return {
    stage(buildName) {
      node('Docker') {
        def gitUrl = "http://doocs-git.desy.de/cgit/doocs/library/${libraryName}.git"
        if (env.USE_GITLAB && env.USE_GITLAB != '') {
          gitUrl = "https://mcs-gitlab.desy.de/${libraryName}.git"
        }
        if (env.BRANCH_NAME && env.BRANCH_NAME != '') {
          git branch: env.BRANCH_NAME, url: gitUrl
        } else {
          git gitUrl
        }
        // we need root access inside the container
        def dockerArgs = "-u 0 --privileged -v /home/msk_jenkins:/home/msk_jenkins"
        docker.image("builder:${label}").inside(dockerArgs) {
          script {
            sh '''
              mkdir /scratch
              mkdir -p /export/doocs/library/common
            '''
            helper.doDependencyArtefacts(dependencyList, label, buildType, helper.jekinsProjectToDependency(JOB_NAME))

            // Compute name where to put the install artifact
            def installArtifactFile = helper.getArtefactName(false, "install.tgz", label, buildType, JOB_NAME)

            // We don't care that in gitlab the repository structure is different. Those project only work with meson builds anyway, and form them the path does not matter.
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
                sudo -E -H -u msk_jenkins mkdir -p /export/doocs/lib/pkgconfig
                find /export/doocs/lib/pkgconfig -name *.pc -exec sed -i \\{\\} -e 's/Requires.private: libtirpc//' \\;
                
                sudo -E -H -u msk_jenkins meson build --wrap-mode=nofallback --buildtype=\${buildType} --prefix=/export/doocs --libdir 'lib' --includedir 'lib/include' -Db_lundef=false
                sudo -E -H -u msk_jenkins ninja -C build
                find /export > /export.list.before
                sudo -E -H -u msk_jenkins ninja -C build install
                find /export > /export.list.after
              else
                source /export/doocs/doocsarch.env
                export LSAN_OPTIONS=verbosity=1:log_threads=1
                make ${MAKEOPTS}
                find /export > /export.list.before
                make install
                find /export > /export.list.after
              fi
              cd "$WORKSPACE"
              diff /export.list.before /export.list.after | grep "^> " | sed -e 's/^> //' > export.list.installed
              touch /scratch/artefact.list
              mv /scratch/artefact.list /scratch/dependencies.${JOBNAME_CLEANED}.list
              echo /scratch/dependencies.${JOBNAME_CLEANED}.list >> export.list.installed
              sudo -H -u msk_jenkins tar cf ${installArtifactFile} --files-from export.list.installed --use-compress-program="pigz -9 -p32"
            """
          }
        }
      }
    }
  }
}

/**********************************************************************************************************************/


