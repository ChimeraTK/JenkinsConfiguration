/***********************************************************************************************************************

  Pipeline script for the Dragon Nightly reporter jobs, which pickup the artefacts for a single project from the
  Dragon Nightly job and reports the results. The reporter jobs are created automatically by the Dragon Nightly job.

  For more information about the general concept, see dragon_nightly.groovy.

***********************************************************************************************************************/

/**********************************************************************************************************************/

def call(String name) {
    pipeline {
        agent none

        // configure discarding of old builds/artefacts
        options {
            buildDiscarder(logRotator(numToKeepStr: '15'))
        }

        stages {
            stage('Generate report') {
                // Run the dragonRunnerfor all labels + build types in parallel
                steps {
                    script {
                        parallel dragon_builds.getBuilds().collectEntries { ["${it}" : transformIntoStep(name, it)] }
                    }
                }
            }
            stage('Scan for warnings') {
                steps {
                    node('Docker') {
                        script {
                            // report compiler warnings
                            recordIssues filters: [excludeMessage('.*-Wstrict-aliasing.*')], qualityGates: [[threshold: 1, type: 'TOTAL', unstable: true]], tools: [gcc()], sourceDirectory: '/scratch/dragon/sources'
                        }
                    }
                }
            }
        }
    }
}

/**********************************************************************************************************************/

/*
 * This helper function is used to generate one step for each build and call generateReport() in it
 */
def transformIntoStep(String name, String buildName) {
  // split the build name at the '-'
  def (label, buildType) = buildName.tokenize('-')

  // we need to return a closure here, which is then passed to parallel() for execution
  return {
    stage(buildName) {
      generateReport(name, label, buildType)
    }
  }
}

/**********************************************************************************************************************/

def generateReport(String name, String label, String buildType) {
    node('Docker') {
        script {
            // extract artefact
            sh """
                pwd
                rm -rf *
                tar xf "${dragon_builds.getArtefactsDir()}/${label}/${buildType}/${name}.tar.gz"
            """

            // replay log
            sh """
                echo ================= configure ==================
                cat .dragon.configure.log
                echo ================= build ==================
                cat .dragon.build.log
                echo ================= configure ==================
                cat .dragon.test.log
            """

            // Change names of tests inside ctest XML files to make test results from different builds distinguishable
            sh """
                if [ -f CTestTestfile.cmake ]; then
                    sed -i Testing/*/Test.xml -e 's|\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$|\\1${label}.${buildType}.\\2\\3|'
                fi
            """

            // report ctest results
            if(fileExists('CTestTestfile.cmake')) {
                xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ], tools: [ CTest(pattern: "Testing/*/*.xml") ])
            }

            // report build/test failures through job status
            if(!fileExists('.dragon.build.success')) {
              echo("================= FAIL ==================")
              if(buildType != 'asan' && buildType != 'tsan') {
                currentBuild.result = 'ERROR'
              }
              else {
                // asan/tsan failures will only warn
                currentBuild.result = 'UNSTABLE'
              }
            }
            else {
              echo("================= SUCCESS ==================")
            }

        }
    }
}

/**********************************************************************************************************************/
