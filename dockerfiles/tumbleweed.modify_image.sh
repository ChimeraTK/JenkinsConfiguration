#/bin/bash

# Unfortunately some of the package installations on tumbleweed require the
# --security-opt seccomp:unconfined flag, which is not available when building an image. So here is the work-around:

# Create a container with the required flag and start it
docker create -i --security-opt seccomp:unconfined --name tumbleweed_updater builder:tumbleweed
docker start tumbleweed_updater

# Execute the required commands in the container
docker exec tumbleweed_updater zypper -n install git libboost_*-devel libqt5-qtbase-devel rpcbind
docker exec tumbleweed_updater useradd "-u" 30996 msk_jenkins
docker exec tumbleweed_updater bash "-c" "echo \"Defaults set_home\" >> /etc/sudoers"
docker exec tumbleweed_updater git config --system http.proxy http://xfelproxy.desy.de:3128 
docker exec tumbleweed_updater git config --system https.proxy http://xfelproxy.desy.de:3128

# Stop the container and commit the changes to the image
docker stop tumbleweed_updater
docker commit tumbleweed_updater builder:tumbleweed

# Finally remove the container
docker container rm tumbleweed_updater
