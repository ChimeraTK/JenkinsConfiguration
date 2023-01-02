/***********************************************************************************************************************

  autojob() is called from the .jenkinsfile of each project to automatically detect the necessary actions depending
  on the organization folder name.

***********************************************************************************************************************/

def call(ArrayList<String> dependencyList, String gitUrl='',
         ArrayList<String> builds=['focal-Debug',
                                   'focal-Release',
                                   'focal-tsan',
                                   'focal-asan']) {
//                                   'tumbleweed-Debug',
//                                   'tumbleweed-Release']) {


  def (organisation, job_type, project, branch) = env.JOB_NAME.tokenize('/')
  env.ORGANISATION = organisation
  env.JOB_TYPE = job_type
  env.PROJECT = project
  env.BRANCH = branch

  if(job_type == 'fasttrack' || job_type == 'branches') {
    // setup build trigger
    // Note: do not trigger automatically by from upstream projects, since this is implemented explicitly to have more
    // control. The dependencies are tracked above in the scripts section in a central "database" and used to trigger
    // downstream build jobs after the build.
    // Exception: if this job has no dependency, make sure it gets triggered when docker images are renewed
    if(!dependencyList.isEmpty()) {
      properties([pipelineTriggers([pollSCM('* * * * *')])])
    }
    else { 
      properties([pipelineTriggers([pollSCM('* * * * *'), upstream('Create Docker Images')])])
    }

    // build projects (without tests) and generate the artefacts
    env.DISABLE_TEST=true
    buildAndDeploy(dependencyList, gitUrl, ['focal-Debug'])
  }
  else if(job_type == 'fasttrack-testing' || job_type == 'branches-testing') {
    testing(['focal-Debug'])
  }
  else if(job_type == 'nightly-testing') {
    analysis()
  }
  else if(job_type == 'nightly') {
    // do not trigger this job type on its own, since this is done by one manager job
    properties([pipelineTriggers()])

    // build projects (without tests) and generate the artefacts
    env.DISABLE_TEST=true
    buildAndDeploy(dependencyList, gitUrl, builds)
  }
  else {
    echo("Unknown job type: ${job_type}")
    currentBuild.result = 'FAILURE'
  }

}
