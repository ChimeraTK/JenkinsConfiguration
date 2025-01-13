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
                  catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    script {
                        parallel dragon_builds.getBuilds().collectEntries { ["${it}" : transformIntoStep(name, it)] }
                    }
                  }
                }
            }
            stage('Scan for warnings') {
                steps {
                    node('Docker') {
                        script {
                            // report compiler warnings
                            recordIssues filters: [excludeMessage('.*-Wstrict-aliasing.*')], qualityGates: [[threshold: 1, type: 'TOTAL', unstable: true]], tools: [gcc()], sourceDirectory: '/scratch/dragon/sources', enabledForFailure: true
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
                PWD=`pwd`
                #rm -rf "\${PWD}"
                #mkdir "\${PWD}"
                find ${PWD} -mindepth 1 -delete
                cd "\${PWD}"
                tar xf "${dragon_builds.getArtefactsDir()}/${label}/${buildType}/${name}.tar.gz"
            """

            // replay log
            sh """
                echo ================= configure ==================
                cat .dragon.configure.log
                echo ================= build ==================
                cat .dragon.build.log
                echo ================= tests ==================
                if [ -e .dragon.test.log ]; then
                  cat .dragon.test.log
                else
                  echo "*** NO TESTS EXECUTED ***"
                fi
            """

            // Change names of tests inside ctest XML files to make test results from different builds distinguishable
            sh """
                if [ -f CTestTestfile.cmake ]; then
                    sed -i Testing/*/Test.xml -e 's|\\(^[[:space:]]*<Name>\\)\\(.*\\)\\(</Name>\\)\$|\\1${label}.${buildType}.\\2\\3|'
                fi
            """

            // report ctest results
            if(fileExists('CTestTestfile.cmake')) {
              if(buildType != 'asan' && buildType != 'tsan') {
                xunit (thresholds: [ skipped(failureThreshold: '0'), failed(failureThreshold: '0') ], tools: [ CTest(pattern: "Testing/*/*.xml") ])
              }
              else {
                xunit (thresholds: [ skipped(unstableThreshold: '0'), failed(unstableThreshold: '0') ], tools: [ CTest(pattern: "Testing/*/*.xml") ])
              }
            }

            // report build failures through job status (test failures are reported through xunit plugin)
            if(!fileExists('.dragon.build.success')) {
              echo("================= BUILD FAILED ==================")
              if(buildType != 'asan' && buildType != 'tsan') {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    sh "exit 1"
                }
              }
              else {
                // asan/tsan failures will only warn
                currentBuild.result = 'UNSTABLE'
              }
            }
            else {
              echo("================= BUILD SUCCESS (for test result, see xunit plugin) ==================")
            }

        }
    }
}

/**********************************************************************************************************************/
