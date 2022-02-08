/***********************************************************************************************************************

  autojob() is called from the .jenkinsfile of each project to automatically detect the necessary actions in dependence
  of the organization folder name.

***********************************************************************************************************************/

def call(ArrayList<String> dependencyList, String gitUrl='',
         ArrayList<String> builds=['focal-Debug',
                                   'focal-Release',
                                   'focal-tsan',
                                   'focal-asan']) {
//                                   'tumbleweed-Debug',
//                                   'tumbleweed-Release']) {


  def (organisation, job_type, project) = env.JOB_NAME.tokenize('/')
  env.ORGANISATION = organisation
  env.JOB_TYPE = job_type
  env.PROJECT = project

  if(job_type == 'fastpath') {
    env.DISABLE_TEST=true
    buildTestDeploy(dependencyList, gitUrl, ['focal-Debug'])
  }
  else {
    echo("Unknown job type: ${job_type}")
    currentBuild.result = 'FAILURE'
  }

}
