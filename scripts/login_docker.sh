#!/bin/bash

if [ $# != 1 -a $# != 4 ]; then
  echo "Usage: ./login_docker.sh <label> [<jobName> <buildType> <buildNumber>]"
  echo "  <label> denomiates the Linux system name, e.g. xenial, bionic, focal, tubleweed etc."
  echo "  <jobName> denomiates a Jenkins job name whose build artefact and dependencies should be unpacked into the container."
  echo "  <buildType> denonminates the cmake build type to retreive the job and artefacts for, i.e. Debug or Release."
  echo "  <buildNumbger> denonminates the Jenkins build number to retreive the build artefact for. Dependency artefacts are always retreived as lastSuccessfulBuild!"
  exit 1
fi

label=$1
# replace all slashes with underscores in jobName
jobName=${2//\//_}
buildType=$3
buildNumber=$4

IFS='/' read -r -a jobNameArray <<< "$2"
jobType=${jobNameArray[1]}

ARTIFACTS="/home/msk_jenkins/artifacts"

# should be the same as in the pipeline script (excluding the -u 0)
DOCKER_PARAMS="--shm-size=1GB --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy -v /opt/matlab_R2016b:/opt/matlab_R2016b"

# create and start container (use options to allow gdb inside!)
ID=`docker create --cap-add=SYS_PTRACE --security-opt seccomp=unconfined -i ${DOCKER_PARAMS} -v /home/msk_jenkins:/home/msk_jenkins builder:${label}`
docker start ${ID} || exit 1

(
  set -e

  # download and unpack build artefact
  if [ -n "${jobName}" ]; then
    echo "Find & unpack artefact build-${jobName}-${label}-${buildType}.tgz..."
    sleep 2

   artefact=${ARTIFACTS}/${jobName}/${label}/${buildType}/${buildNumber}/build.tgz
   tar xf ${artefact} scratch/artefact.list || true
    docker exec -u 0 -it ${ID} tar xf ${artefact}

    # download and unpack dependency artefacts
    if [ -f scratch/artefact.list ]; then
      for dep in `cat scratch/artefact.list` ; do
        echo "Find & unpack dependency artefact install-${dep}-${label}-${buildType}.tgz..."
        sleep 1
        # dependencies have are formatted like 'ChimeraTK/ApplicationCore@1571'
        depBuildNumber=${dep##*@}
        # remove build Number
        depJobName=${dep%@*}
        # in artifacs, look for folder named like ChimeraTK_fasttrack_ApplicationCore_master/, if it does not exist, look for ChimeratTK_ApplicationCore/
        # replace first slash by _fasttrack_, append _master
        depJobName1=${depJobName/\//_${jobType}_}_master
        # replace further slashes by %2F
        depJobName1=${depJobName1//\//%2F}
        depArtefactFolder=$ARTIFACTS/${depJobName1}
        if [ ! -d ${depArtefactFolder} ] ; then
          depJobName2=${depJobName/\//_}
          # replace further slashes by %2F
          depJobName1=${depJobName1//\//%2F}
          depArtefactFolder=$ARTIFACTS/${depJobName2}
        fi
        depArtefact=${depArtefactFolder}/${label}/${buildType}/${depBuildNumber}/install.tgz
        docker exec -u 0 -it ${ID} tar xf ${depArtefact}
      done
      rm scratch/artefact.list
      rmdir scratch
    fi

  fi

  # fix access rights
  docker exec -u 0 -it ${ID} bash -il -c "chown msk_jenkins -R /scratch || true"

  # Pass on HTTP proxy variables during sudo
  docker exec -u 0 -it ${ID} bash -il -c "echo 'Defaults env_keep += \"http_proxy https_proxy\"' >> /etc/sudoers"

  # enable password-less sudo for msk_jenkins inside the container
  docker exec -u 0 -it ${ID} bash -il -c "echo 'msk_jenkins ALL = (ALL) NOPASSWD: ALL' >> /etc/sudoers"

  # start interactive shell
  echo "==========================================================================================================="
  echo " Starting interactive shell in the docker container for ${label} as user msk_jenkins."
  echo " Password-less sudo has been enabled inside this container (in contrast to the Jenkins build environment)."
  echo "==========================================================================================================="
  docker exec -u msk_jenkins -it ${ID} bash -il

)

# tear down container
echo "Stopping and removing the container. Please be patient..."
docker stop ${ID}
docker container rm ${ID}
