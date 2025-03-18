#!/bin/bash

if [ $# != 1 -a $# != 2 -a $# != 3 ]; then
  echo "Usage: ./login_docker.sh <label> [<buildType> [<jobName>]]"
  echo "  <label> denomiates the Linux system name, e.g. noble, tubleweed etc."
  echo "  <buildType> denonminates the build type to retreive the job and artefacts for, i.e. debug, release, asan or tsan."
  echo "  <jobName> denomiates a cmake project name whose build artefact should be unpacked into the container."
  exit 1
fi

label=$1
buildType=$2
projectName=$3

ARTEFACTS="/home/msk_jenkins/dragon-artefacts"

# should be the same as in the pipeline script (excluding the -u 0)
DOCKER_PARAMS="--privileged --shm-size=1GB --device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 --device=/dev/ctkuiodummy -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy -v /opt/matlab_R2016b:/opt/matlab_R2016b"

# create and start container (use options to allow gdb inside!)
ID=`docker create --cap-add=SYS_PTRACE --security-opt seccomp=unconfined -i ${DOCKER_PARAMS} -v /home/msk_jenkins:/home/msk_jenkins builder:${label}`
docker start ${ID} || exit 1

(
  set -e

  docker exec -u 0 -it ${ID} bash -c "mkdir -p /scratch ; cp -r /home/msk_jenkins/dragon /scratch"

  # download and unpack install artefact
  if [ -n "${buildType}" ]; then
    artefact=${ARTEFACTS}/${label}/${buildType}/install-*.tar.gz
    targetdir="/scratch/dragon/install-${buildType}"
    docker exec -u 0 -it ${ID} bash -c "mkdir -p ${targetdir} ; cd ${targetdir} ; tar xf ${artefact} --keep-directory-symlink"

    if [ -n "${projectName}" ]; then
      # download and unpack build artefact
      artefact="${ARTEFACTS}/${label}/${buildType}/${projectName}.tar.gz"
      targetdir="/scratch/dragon/build/${projectName}-${buildType}"
      docker exec -u 0 -it ${ID} bash -c "mkdir -p ${targetdir} ; cd ${targetdir} ; tar xf ${artefact} --keep-directory-symlink"
    fi
  fi

  # fix access rights
  docker exec -u 0 -it ${ID} bash -il -c "chown msk_jenkins -R /scratch || true"

  # Pass on HTTP proxy variables during sudo
  docker exec -u 0 -it ${ID} bash -il -c "echo 'Defaults env_keep += \"http_proxy https_proxy\"' >> /etc/sudoers"

  # enable password-less sudo for msk_jenkins inside the container
  docker exec -u 0 -it ${ID} bash -il -c "echo 'msk_jenkins ALL = (ALL) NOPASSWD: ALL' >> /etc/sudoers"

  # symlink uio-dummy device
  docker exec -u 0 -it ${ID} bash -il -c "ln -sf /dev/uio* /dev/ctkuiodummy"

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
