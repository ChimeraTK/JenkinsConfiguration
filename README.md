# JenkinsConfiguration
Shared libraries for Jenkins CI tests of ChimeraTK libraries and DESY MSK projects

Usage:
 
  - Create a pipline job "Dragon Nightly" on Jenkins with the following pipeline script:
```
@Library('ChimeraTK') _
dragon_nightly()
```
  - Beware the underscore at the end of the first line!
  - It will create the reporter jobs automatically once done.
