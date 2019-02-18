# JenkinsConfiguration
Shared libraries for Jenkins CI tests of ChimeraTK libraries and DESY MSK projects

Usage:
 
  - Create a pipline job on Jenkins with the following pipeline script:
```
@Library('ChimeraTK') _
buildTestDeploy(['dependency1', 'dependency2'], 'https://path/to/git/repository')
```
  - Beware the underscore at the end of the first line!
  - The list of dependencies can be empty, if there are no dependencies
  - The dependencies specify the Jenkins project names of which the artefacts should be obtained and unpacked into
    the root directory of the Docker environment before starting the build.
  - Execute the job once, this will fill in the project triggers etc. properly

  - Create a second pipeline job on Jenkins with the name of the main job appended by '-analysis' (e.g. 'ChimeraTK-DeviceAccess-analysis' if the first job is called 'ChimeraTK-DeviceAccess').
  - Put in the following pipeline script:
```
@Library('ChimeraTK') _
analysis()
```
  - Execute this job once after the main job is finished for the first time, to also fill in the project triggers etc. for the analysis job.

# Important
 
  - Tests will be executed concurrently via "ctest -j" etc. inside the same docker environment. It is expected that the
    tests are either designed to not interfere with each other or to internally use locks to exclude concurrent access
    to the same ressource (e.g. network port, shared memory)
  - The mtcadummy devices are available inside the docker environments, but they are even shared across the different
    containers (since all containers run under the same kernel). It is expected that tests using these dummies use
    file locks on /var/run/lock/mtcadummy/<devicenode> via flock() to ensure exclusive access. /var/run/lock/mtcadummy
    will be a shared directory between all containers sharing the same kernel.

# Optional configuration

It is possible to add some per-project exceptions to the configuration. This can be done by setting variables before the call to `buildTestDeploy()` resp. `analysis()` in the pipeline script as explained in the followin.

## Main jobs
- Prevent tests from being run in parallel:
```
env.CTESTOPTS="-j1"
```

- Run only part of a repository from a sub directory:
  (Usually used examples which come with the source code and have their own, stand-alone CMakeLists.txt to be used with the installed main library.)
```
env.RUN_FROM_SUBDIR="examples"
```

- Set extra environment variables:
The variables are gives as space separated lists of key=values pairs. The pairs must not have spaces. JOB_VARIABLES are applied to build and tests (incl. analysis), while TEST_VARIABLES are only in tests and analysis.
```
env.JOB_VARIABLES="A=aha B=bubu"
env.TEST_VARABLES="PATH=${PATH}:/some/path/for/the/test"
```


## Analysis jobs
- Exclude tests from being run in valgrind (currently only possible with a single test):
```
env.valgrindExcludes="nameOfTestToExclude"
```

# Background information
 
 - Builds and tests are run inside several Docker containers to have different test environments (e.g. Ubuntu 16.04 and SuSE Tumbleweed)
 - We execute all builds/tests twice per test environment - once for Debug and once for Release. Each execution is
   called a "branch".
 - Docker only virtualises the system without the kernel - the PCIe dummy driver is therefore shared!
 - Most Jenkins plugins do not support their result publication executed once per branch, thus we stash the result files
   and execute the publication later for all branches together.
