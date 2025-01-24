/***********************************************************************************************************************

  Pipeline script for the Dragon Nightly job, which builds and tests all projects using Dragon.

  The idea is to run all builds and tests within the single Dragon Nightly job (all projects sequentially) and
  generate artefacts containing the build directories (including log files) for each project and build type (like
  focal-Debug or tumbleweed-Release). Once this is complete, a so-called reporter job for each project (but not per
  build type) is created and triggered, which will pick up the artefacts for the corresponding job and publish the
  result (as if the build/test would have been run inside that reporter job).

  The Dragon Nightly job should never fail, its status should not be related to any build or test failures.

  Each nightly run should be fully separated from the previous runs, no dependencies shall be taken from earlier runs.
  This means that a build failure in one library will result in a failure in all depending projects, because they won't
  find the failed library.

  The script requires a working installation of Dragon in /home/msk_jenkins/dragon, with properly setup API keys for
  github and gitlab. This can best be done manually in the jenkins build node VM as msk_jenkins user.

***********************************************************************************************************************/

import org.jenkinsci.plugins.workflow.cps.*;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

/**********************************************************************************************************************/

/*
 * This is the main function called from the dragon nightly Jenkins job.
 */
def call() {
    pipeline {
        agent none

        // configure job options
        options {
            buildDiscarder(logRotator(numToKeepStr: '15'))    // discard old builds, just keep the latest 15 ones
            timeout(time: 10, unit: 'HOURS')                  // abort stuck build
            timestamps()                                      // enable timestamps in log messages
            disableConcurrentBuilds()                         // do not run job in parallel with itself (e.g. manually
                                                              // triggered build)
        }

        // run every evening
        triggers {
            cron('H 19 * * *')
        }

        stages {
            stage('Update dragon database and sources') {
                steps {
                    script {
                        node('Docker') {
                            sh """
                                export PATH=/home/msk_jenkins/bin:$PATH
                                cd /home/msk_jenkins/dragon
                                git pull
                                source /home/msk_jenkins/dragon/bin/setup.sh
                                dragon updatedb
                                dragon select --all
                                dragon update --https --reset-url --default-branch --reset-hard-and-clean --orphan-on-failure
                                rm -rf "${dragon_builds.getArtefactsDir()}"
                                dragon list --selected > \${WORKSPACE}/joblist.txt
                            """
                        }
                    }
                }
            }
            stage('Run dragon') {
                // Run the dragonRunnerfor all labels + build types in parallel
                steps {
                  script {
                    parallel dragon_builds.getBuilds().collectEntries { ["${it}" : transformIntoStep(it)] }
                  }
                }
            }
            stage('Trigger reporter jobs') {
                steps {
                    script {
                        node('Docker') {
                            def file = readFile 'joblist.txt'
                            def lines = file.readLines()
                            lines.each { name -> reporterRunner(name) }
                        }
                    }
                }
            }
        }
    }
}

/**********************************************************************************************************************/

/*
 * dragonRunner() function is called once per build, each in a separate stage, to execute the actual build and test
 * inside a docker container. It will also generate the artefact containing the build directory and the logs.
 */
def dragonRunner(String label, String build) {
    script {
        node('Docker') {
            def dragonbuild = build != 'tag' ? build : 'debug'
            def uioFile = sh (returnStdout: true, script: 'readlink /dev/ctkuiodummy')
            def dockerArgs = "-u 0 --privileged --shm-size=1GB --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 --device=/dev/${uioFile} -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy -v /opt/matlab_R2016b:/opt/matlab_R2016b -v /home/msk_jenkins:/home/msk_jenkins --network ip6net"
            docker.image("builder:${label}").inside(dockerArgs) {
              withCredentials([usernamePassword(credentialsId: 'jenkins-github-chimeratk', usernameVariable: 'GITHUB_APP', passwordVariable: 'GH_TOKEN')]) {
              withCredentials([string(credentialsId: 'gitllab-msksw-group-access-token-as-text', variable: 'GITLAB_TOKEN')]) {
                sh """
                    mkdir -p /scratch
                    chown msk_jenkins:msk_jenkins /scratch
                    ln -sf /dev/uio* /dev/ctkuiodummy
                    cat > /scratch/script <<EOF
#!/bin/bash
cd /scratch
cp -r /home/msk_jenkins/dragon/ dragon/
source dragon/bin/setup.sh

if [ ${build} == tag ]; then
  dragon update --greatest-tag --orphan-on-failure
fi
echo ===================================================================================================
echo PWD for ${build}:
pwd
echo ENVIRONMENT for ${build}:
env
echo WHOAMI for ${build}:
whoami
echo GLAB AUTH STATUS for ${build}:
glab auth status
echo /scratch/dragon/sources/AmtfDbAccess: git remote get-url origin for ${build}:
cd /scratch/dragon/sources/AmtfDbAccess
git remote get-url origin
cd /scratch
echo PWD for ${build}:
pwd
echo continue for ${build}:

echo ===================================================================================================
echo ==== Running dragon build
time dragon build -t ${dragonbuild} -k
echo ===================================================================================================
echo ==== Running test build
time timeout 7200 dragon test -t ${dragonbuild}
echo ===================================================================================================
echo ==== Running git describe
time dragon foreach -k -t source \'git describe --tags --always > \\\${DIRS_${dragonbuild}}/.git-describe\'
echo ===================================================================================================
echo ==== Creating build artefacts
time dragon foreach -k -t ${dragonbuild} tar zcf /scratch/\\\\\\\${PROJECT}.tar.gz .
echo ===================================================================================================
echo ==== Creating install artefact
time tar zcf /scratch/install-${label}-${dragonbuild}.tar.gz -C /scratch/dragon/install-${dragonbuild} .
echo ===================================================================================================
echo ==== Storing artefacts
mkdir -p "${dragon_builds.getArtefactsDir()}/${label}/${build}"
pwd
cd "${dragon_builds.getArtefactsDir()}/${label}/${build}"
cp -v /scratch/*.tar.gz .
echo ===================================================================================================
echo ==== All done, terminating
EOF
                cat /scratch/script
                chmod +x /scratch/script
                echo ---- launching script...
                sudo -H -E -u msk_jenkins /scratch/script
                echo ---- script has terminated...
                """
              }
            }}
        }
    }
}

/**********************************************************************************************************************/

/*
 * reporterRunner() function is called for each job after all dragonRunner() stages are finished. It creates the
 * reporter jobs if necessary and triggers them. The reporter jobs will pickup the artefacts from the dragonRunner()
 * and publish the results.
 */
@NonCPS
def reporterRunner(name) {
    def jobname = name.replaceAll('@', '_at_')
    def folder = jenkins.model.Jenkins.instance.getItem("Dragon Nightly Reporters")
    def item = folder.getItem(jobname)
    if(item == null) {
        echo "Need to create job dragon/${jobname} first..."
        WorkflowJob p = folder.createProject(WorkflowJob.class, jobname)
        def jobScript = """@Library('ChimeraTK') _
dragon_reporter(\"${name}\")"""
        p.setDefinition(new CpsFlowDefinition(jobScript, true))
        p.save()
    }
    build(job: "Dragon Nightly Reporters/${jobname}", propagate: false)
}

/**********************************************************************************************************************/

/*
 * This helper function is used to generate one step for each build and call the dragonRunner() in it
 */
def transformIntoStep(String buildName) {
  // split the build name at the '-'
  def (label, buildType) = buildName.tokenize('-')

  // we need to return a closure here, which is then passed to parallel() for execution
  return {
    stage(buildName) {
      dragonRunner(label, buildType)
    }
  }
}

/**********************************************************************************************************************/
