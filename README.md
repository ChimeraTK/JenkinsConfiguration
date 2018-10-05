# JenkinsConfiguration
Shared libraries for Jenkins CI tests of ChimeraTK libraries and DESY MSK projects

 Usage:
 
 - In the git repository of the project source create a file named ".jenkinsfile"
 - Place the following content into that file (no indentation):
    @Library('ChimeraTK') _
    buildTestDeploy(['dependency1', 'dependency2']) 
  - Beware the underscore at the end of the first line!
  - The list of dependencies is optional, just call buildTestDeploy() if there are no dependencies
  - The dependencies specify the Jenkins project names of which the artefacts should be obtained and unpacked into
    the root directory of the Docker environment before starting the build.

    
 Important:
 
  - Tests will be executed concurrently via "ctest -j" etc. inside the same docker environment. It is expected that the
    tests are either designed to not interfere with each other or to internally use locks to exclude concurrent access
    to the same ressource (e.g. network port, shared memory)
  - The mtcadummy devices are available inside the docker environments, but they are even shared across the different
    containers (since all containers run under the same kernel). It is expected that tests using these dummies use
    file locks on /var/run/lock/mtcadummy/<devicenode> via flock() to ensure exclusive access. /var/run/lock/mtcadummy
    will be a shared directory between all containers sharing the same kernel.
 

 General comments:
 
 - Builds and tests are run inside a Docker container to have different test environments
 - We execute all builds/tests twice per test environment - once for Debug and once for Release. Each execution is
   called a "branch".
 - Docker only virtualises the system without the kernel - the PCIe dummy driver is therefore shared!
 - Most Jenkins plugins do not support their result publication executed once per branch, thus we stash the result files
   and execute the publication later for all branches together.
   
 - Important: It seems that some echo() are necessary, since otherwise the build is failing without error message. The
   impression might be wrong and the reasons are not understood. A potential explanation might be that sometimes empty
   scripts (e.g. the part downloading the artefacts when no artefacts are present) lead to failure and an echo() makes
   it not empty. The explanation is certainly not complete, as only one of the branches fails in these cases. This
   should be investigated further.

