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


  def (organisation, job_type, project, branch) = env.JOB_NAME.tokenize('/')
  env.ORGANISATION = organisation
  env.JOB_TYPE = job_type
  env.PROJECT = project
  env.BRANCH = branch

  if(job_type == 'fasttrack' || job_type == 'branches') {
    env.DISABLE_TEST=true
    buildAndDeploy(dependencyList, gitUrl, ['focal-Debug'])
  }
  else if(job_type == 'fasttrack-testing' || job_type == 'branches-testing') {
    testing()
  }
  else {
    echo("Unknown job type: ${job_type}")
    currentBuild.result = 'FAILURE'
  }

}
