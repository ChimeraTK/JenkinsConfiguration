/***********************************************************************************************************************

  nightly_manager() is called from the nightly manager job

***********************************************************************************************************************/

def call() {

  script {
    helper.setParameters()

    node('Docker') {
      
      // put all our projects into the build plan
      sh """
        for f in /home/msk_jenkins/dependency-database/jobnames/*; do
          (cat "\${f}"; echo) >> ${WORKSPACE}/joblist.txt
        done
      """
      def joblist = readFile(env.WORKSPACE+"/joblist.txt")
      helper.BUILD_PLAN = joblist.tokenize('\n')
      def JobNameAsDependency = helper.jekinsProjectToDependency(JOB_NAME)
      helper.DEPENDENCY_BUILD_NUMBERS = [ "${JobNameAsDependency}" : BUILD_NUMBER ]
      
      env.JOB_TYPE = "nightly"

    } // docker

    // trigger periodically (nightly)
    properties([pipelineTriggers([cron('0 22 * * *')])])
    
  } // script

  pipeline {
    agent none

    // configure discarding of old builds/artefacts
    options {
      quietPeriod(0)
      buildDiscarder(logRotator(numToKeepStr: '15'))
    }

    stages {
      // run all downstream builds
      stage('downstream-builds') {
        when {
          // downstream builds are run only in the first job, i.e. not when being triggered by an upstream dependency
          expression { return helper.BRANCH_UNDER_TEST == JOB_NAME }
        }
        steps {
          script {
            helper.doDownstreamBuilds(true)
          }
        }
      } // stage downstream-builds
    } // end stages
  } // end pipeline


}
