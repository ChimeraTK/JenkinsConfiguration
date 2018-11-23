#!/bin/bash

if [ $# != 1 -a $# != 4 ]; then
  echo "Usage: ./login_docker.sh <label> [<jobName> <buildType> <buildNumber>]"
  echo "  <label> denomiates the Linux system name, e.g. xenial, bionic, tubleweed etc."
  echo "  <jobName> denomiates a Jenkins job name whose build artefact and dependencies should be unpacked into the container."
  echo "  <buildType> denonminates the cmake build type to retreive the job and artefacts for, i.e. Debug or Releas."
  echo "  <buildNumbger> denonminates the Jenkins build number to retreive the build artefact for. Dependency artefacts are always retreived as lastSuccessfulBuild!"
  exit 1
fi

label=$1
jobName=$2
buildType=$3
buildNumber=$4

# should be the same as in the pipeline script (excluding the -u 0)
DOCKER_PARAMS="--device=/dev/mtcadummys0 --device=/dev/mtcadummys1 --device=/dev/mtcadummys2 --device=/dev/mtcadummys3 --device=/dev/llrfdummys4 --device=/dev/noioctldummys5 --device=/dev/pcieunidummys6 -v /var/run/lock/mtcadummy:/var/run/lock/mtcadummy"

# create and start container (use options to allow gdb inside!)
ID=`docker create --cap-add=SYS_PTRACE --security-opt seccomp=unconfined -i ${DOCKER_PARAMS} -v /home/msk_jenkins:/home/msk_jenkins builder:${label}`
docker start ${ID} || exit 1

(
  set -e

  # download and unpack build artefact
  if [ -n "${jobName}" ]; then
    echo "Downloading artefact build-${jobName}-${label}-${buildType}.tgz..."
    sleep 2
    wget --no-check-certificate "https://mskllrfredminesrv.desy.de/jenkins/job/${jobName}/${buildNumber}/artifact/build-${jobName}-${label}-${buildType}.tgz" -O artefact.tgz
    tar xf artefact.tgz scratch/artefact.list
    docker exec -u 0 -it ${ID} tar xf /home/msk_jenkins/artefact.tgz
    rm artefact.tgz

    # download and unpack dependency artefacts
    for dep in `cat scratch/artefact.list` ; do
      echo "Downloading artefact install-${dep}-${label}-${buildType}.tgz..."
      sleep 2
      wget --no-check-certificate "https://mskllrfredminesrv.desy.de/jenkins/job/${dep}/lastSuccessfulBuild/artifact/install-${dep}-${label}-${buildType}.tgz" -O artefact.tgz
      docker exec -u 0 -it ${ID} tar xf /home/msk_jenkins/artefact.tgz
      rm artefact.tgz
    done

    rm scratch/artefact.list
    rmdir scratch

  fi

  # fix access rights
  docker exec -u 0 -it ${ID} bash -il -c "chown msk_jenkins -R /scratch"

  # enable password-less sudo for msk_jenkins inside the container
  docker exec -u 0 -it ${ID} bash -il -c "echo 'msk_jenkins ALL = (ALL) NOPASSWD: ALL' >> /etc/sudoers"

  # start interactive shell
  echo "==========================================================================================================="
  echo " Starting interactive shell in the docker container for ${label} as user msk_jenkins."
  echo " Password-less sudo has been enabled inside this container (in contrast to the Jenkins build environment)."
  echo "==========================================================================================================="
  docker exec -u 0 -it ${ID} bash -il -c "su -s /bin/bash - msk_jenkins"

)

# tear down container
echo "Stopping and removing the container. Please be patient..."
docker stop ${ID}
docker container rm ${ID}
