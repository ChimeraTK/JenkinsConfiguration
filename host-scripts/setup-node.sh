#!/bin/bash

if [ `hostname` == "mskbuildhost" -o `hostname` == "xfelspare1" -o `hostname` == "mskbuildhost2" ]; then
  echo "Do not run this script on the head node!"
  exit 1
fi

echo "Install required software packages..."
export DEBIAN_FRONTEND=noninteractive
DISTRIB_ID=`lsb_release -i -s`
DISTRIB_RELEASE=`lsb_release -r -s`
if [ "$DISTRIB_ID" = "Ubuntu" -o "$DISTRIB_ID" = "Debian" ]; then
  Jenkins_buildhosts="Ubuntu${DISTRIB_RELEASE/./}"
  # This has been tested with 12.04, 14.04, 16.04 and 18.04
  sudo add-apt-repository -y universe
  apt-get update
  apt-get upgrade -y
  apt-get dist-upgrade -y
  # java for Jenkins CLI
  apt-get install -y default-jre-headless
  # docker
  apt-get install -y docker.io
  # generic build tools
  apt-get install -y g++ valgrind cppcheck lcov doxygen procmail make git gdb
  apt-get install -y cmake
  apt-get install -y dkms
  apt-get install -y gcc-arm-none-eabi
  # for ChimeraTK core libraries:
  apt-get install -y libboost-all-dev libxml++2.6-dev
  # for converting pytest results to xUnit
  apt-get install -y xsltproc
  # for Matlab:
  apt-get install -y libxmu6 csh libxrandr2
  # for projects which use dot graphs in doxygen
  apt-get install -y graphviz
  # NFS for the shared workspace
  apt-get install -y nfs-common
  # linux kernel headers on Debian
  if [ "$DISTRIB_ID" = "Debian" ]; then
    apt-get install -y linux-headers-amd64
  fi
  # execute /etc/rc.local at boot time
  touch /etc/rc.local
  chmod +x /etc/rc.local
  if [ -z "`grep rc.local /etc/crontab`" ]; then
    echo "@reboot         root    /bin/bash -c /etc/rc.local" >> /etc/crontab
  fi
  # clean up old packages
  apt-get autoremove -y
fi

echo "Setup quirks for DOOCS..."
# DOOCS expects libzmq.so.3 but for some reason libzmq3-dev installs libzmq.so.4.0.0
# DOOCS also expects the host "enshost" to serve the ENS service...
# finally rpcbind must be run in insecure mode
if [ -z "`grep ENSHOST /etc/environment`" ]; then
  echo 'export ENSHOST=enshost.desy.de' >> /etc/environment
fi
if [ "$DISTRIB_ID" = "Ubuntu" ]; then
  echo 'OPTIONS="-i -w"' > /etc/default/rpcbind
  ln -sfn /usr/lib/x86_64-linux-gnu/libzmq.so /usr/lib/x86_64-linux-gnu/libzmq.so.3
  if [ "$DISTRIB_RELEASE" = "12.04" ]; then
    service portmap restart
  else
    service rpcbind restart
  fi
fi

if ! id msk_jenkins > /dev/null 2>&1; then
  echo "Create msk_jenkins user and add ssh key..."
  groupadd -g 30996 msk_jenkins
  useradd -m -s /bin/bash -g msk_jenkins -u 30996 msk_jenkins
  mkdir /home/msk_jenkins/.ssh
  echo ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDNrK03xXnfbQrhQJMyIoh5R7j1jmd7xT1khUVDBCOEt+M1LdsA1hqQ6n5cJ598hkS6TlYPY759N4OjxrPpQm9i2VsJC0hAIL33t5SndVRUCNS3VfcIUnpivBonc8D2vuhxbSn95fMu8fQDoPXbZcmcLfmrgYr6ph28K6g1Uunm0rfkWi+5Ej/Zf8x0BX5yjFOkMRpyQfVnrm+vcBkEggCY/LhOqiuR7f7tYpruCAywTn0vyEVoB3iVtxBn87GXTMYzcf6N5cVQ0YxlIlTFv8LlVAOhm/lXDZBZTP3ex0afA9kiyT52TddRFgNPn+XIrqivfuzdXOgr3dl4fkhlSVc9 killenb@mskpcx18571 > /home/msk_jenkins/.ssh/authorized_keys
  echo ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCjcbJ234FhO2zuLWe3vHZ4SN7bRKkUWPL1iG0G1ky0FvnGAhqBcAzTg5EsezlWlH8/o7jvVdgFDr1J4QwM0MS981hqxRbV21UArJt4mXHi3kWQJ9VUhY9WFY1gbrLqxL6pLSxRXHkfIVGqVdCxUf4nodxx38s9STHrVYacnFTsKYxIvdQGmYtsxcunk9aZibM4GHvEFGVhyPqCBSh+309IC23vIz36QoPEgySyOZc9fW+POAtlhyqQltYIxRfwSvxMacSrehVxvewgU6roUD89uXvxdDL7/IlNctuC8ImsxpOQvTQ9vRcHfrTAIyoMrt/5HSkYJDV7QZMzHJ4T1iyj mhier@mskbuildhost >> /home/msk_jenkins/.ssh/authorized_keys
  echo ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDDJ+5nOFnmsewqrSva6pYaTBMW00X/ubKSQMu5SAeEBU56eSDsiZVHaCFowo9RcqAo2WEr+Q4kuC64JzGqvJfilZ+z09RzTpsg2rSqNyYOfYc5jTG7Fl8fXpfobb9Xe6zSRT/YiLescKGUnSm2kUffC6MJh6dRPOktww3afbbwURS/sAhlLHdNMhmzOD9A4IVByX4jkq4tmTvTQm1oWg8JfEQy/LTRyZh5iSqfVb75xSpXMunKz6tJYHG+zhcncTz1MAaIUVI/Q1A2EQZi2IaG/HLSTWbCXxvZaS+6uitOTm3wbA1KzWdlIfSM3OtnrYyX+YlXkhNtLyjRVCuvwc07 jenkins@mskllrfredminesrv >> /home/msk_jenkins/.ssh/authorized_keys
  chown msk_jenkins:msk_jenkins -R /home/msk_jenkins/.ssh
  chmod u+rwX,go-rwX -R /home/msk_jenkins/.ssh
fi

if [ ! -f /root/.ssh/authorized_keys -o -z "`grep root@mskbuildhost /root/.ssh/authorized_keys`" ]; then
  echo "Add ssh key for root..."
  mkdir -p /root/.ssh
  touch /root/.ssh/authorized_keys
  echo ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDhl3/xPGCNgzSr20yF9D8s73b/ntH0w739whKCr4Gr47hVVuflTzVSSckbG7RHbCvubg272Xth3okMCJA1T8OAabGDiGWBg2iqHuH9WtTboZEaEHugcnMyGPRfVi+2XD5+WEKEhmcz+zXIu4MqqQkDrzjxtromxW1LlJFjD4bhwM7fPw14is9797IOPLxd/+nN9MQkj+91Idabf5byJeoS5instk/9axkHXXNariyOOHv7Yl5jx/9ceT3BZHPXu22VpgmicoArK6dZZx+VOJDYpy+T7IEaxiPCrFcdahLTEW0CGffp/gqcpYN9Cy4RRZMhQJ77wYarPhYdrXvyug3p root@mskbuildhost >> /root/.ssh/authorized_keys
  echo ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDAhUo63oSdWugxrG4AEJSZZfKydEDtAjMR2Ncq9v+mgdxuvSFZgPEDKio4VmX78aVG9oPLG5TeqxVv+2L/1F+vBNkow7dx6vHpuhawPuDo2wkupjjvoeTaaxKebC8xroGODl8BPdnJTELC9dOEdD+OPOzxK/0dtWB8iMvyL0Sau7aLmXiPJOPTCLwlFVMZl/uohJTy31i3HdYY7v79KHz6SOOWlTghIIodNkFR7r9oQwM7fNLnwoRLUWM1CsDb5umty5m2VdbZ25yKzqpE8cJp2eIQT5Y3dbM3st1xzK6IEsTN9VOkAvUSG9f8bqOJ8Qg9xvS+pl9babkayW6jwtQJ faimond@xfelbackup1 >> /root/.ssh/authorized_keys
fi

set -e
echo "Install mtcadummy driver..."
pushd .
FOLDER=$(mktemp -d -p /tmp/)
cd "$FOLDER"
git clone https://github.com/ChimeraTK/pciedummy-driver
cd pciedummy-driver
make install
modprobe mtcadummy
popd
rm -rf "$FOLDER"


echo "Install uio-dummy driver..."
FOLDER=$(mktemp -d -p /tmp/)
pushd .
cd $FOLDER
git clone https://github.com/ChimeraTK/uio-dummy
cd uio-dummy
mkdir build
cd build
cmake .. -GNinja -DCMAKE_INSTALL_PREFIX=/usr
ninja install
ninja dkms-remove || true
ninja dkms-install
modprobe uio-dummy
popd
rm -rf "$FOLDER"
set +e

# create lock directory for mtcadummy
mkdir -p /var/run/lock/mtcadummy
chmod u+rwx,g+rwx,o+rwt /var/run/lock/mtcadummy

echo "Install matlab..."
ln -sfn /common/matlab_R2016b /opt/matlab_R2016b
if [ -z "`grep /opt/matlab_R2016b/bin /etc/environment`" ]; then
  # this must be in /etc/environment, because Jenkins does not use /etc/profile
  sed -i /etc/environment -e 's#\(PATH=".*\)"#\1:/opt/matlab_R2016b/bin"#'
fi
if [ ! -f /home/msk_jenkins/.ssh/id_rsa.pem ]; then
  sudo -u msk_jenkins ssh-keygen -f /home/msk_jenkins/.ssh/id_rsa.pem -N ""
  cat /home/msk_jenkins/.ssh/id_rsa.pem.pub >> /home/msk_jenkins/.ssh/authorized_keys
fi

echo "Mount /common at boot and run this script..."
cat <<EOF > /etc/rc.local
#!/bin/sh -e
#
# Warning: This script is overwritten at boot and every day by setup-node.sh
# Do not modify here!
#
mount /common /common -t 9p -o trans=virtio,version=9p2000.L
/common/JenkinsConfiguration/host-scripts/setup-node.sh
exit 0
EOF

echo "Run this script every day via cron..."
cat <<EOF > /etc/cron.daily/setup-node.sh
#!/bin/bash -e
#
# Warning: This script is overwritten at boot and every day by setup-node.sh
# Do not modify here!
#
/common/JenkinsConfiguration/host-scripts/setup-node.sh
EOF
chmod +x /etc/cron.daily/setup-node.sh

echo "Reboot machine once per week..."
cat <<EOF > /etc/cron.d/weekly-reboot
#!/bin/bash -e
#
# Warning: This script is overwritten at boot and every day by setup-node.sh
# Do not modify here!
#
# m h dom mon dow user	command
  0 1 *   *   6   root  shutdown -rf 00:01
EOF
chmod +x /etc/cron.daily/setup-node.sh


if [ -z "`grep desy.de /etc/dhcp/dhclient.conf`" ]; then
  echo "Adding desy.de to DNS search domains..."
  touch /etc/dhcp/dhclient.conf
  sed -i /etc/dhcp/dhclient.conf -e 's/^request.*$/prepend domain-name "desy.de";\n\0/'
  service networking restart
fi

if [ ! -f "/home/msk_jenkins/.ssh/id_rsa" ]; then
  echo "Generating ssh key for msk_jenkins to allow password-less ssh connection to localhost..."
  sudo -u msk_jenkins ssh-keygen -b 2048 -t rsa -f /home/msk_jenkins/.ssh/id_rsa -q -N ""
  cat /home/msk_jenkins/.ssh/id_rsa.pub >> /home/msk_jenkins/.ssh/authorized_keys
fi

#if [ -z "`grep xfelproxy.desy.de /etc/wgetrc`" ]; then
#  echo "Setting up xfel proxy for wget..."
#  echo "https_proxy = http://xfelproxy.desy.de:3128/" >> /etc/wgetrc
#  echo "http_proxy = http://xfelproxy.desy.de:3128/" >> /etc/wgetrc
#fi

if [ ! -f "/home/msk_jenkins/.gitconfig" ]; then
  echo "Setting up git config..."
cat > /home/msk_jenkins/.gitconfig <<EOF
#[http]
#        proxy = xfelproxy.desy.de:3128
#        sslVerify = false

[push]
        default = simple
[user]
        email = msk_jenkins@jenkins-vm
        name = Automated MSK Jenkins User
EOF
fi

if [ -f /etc/openntpd/ntpd.conf ]; then
  if [ -z "`grep time.desy.de /etc/openntpd/ntpd.conf`" ]; then
    echo "Setting up time server..."
    echo "servers time.desy.de" >  /etc/openntpd/ntpd.conf
    echo "servers ntp2.desy.de" >> /etc/openntpd/ntpd.conf
    echo "servers ntp3.desy.de" >> /etc/openntpd/ntpd.conf
    service openntpd restart
  fi
fi
if [ -f /etc/ntp.conf ]; then
  if [ -z "`grep time.desy.de /etc/ntp.conf`" ]; then
    echo "Setting up time server..."
    echo "servers time.desy.de" >  /etc/ntp.conf
    echo "servers ntp2.desy.de" >> /etc/ntp.conf
    echo "servers ntp3.desy.de" >> /etc/ntp.conf
    service ntpd restart
  fi
fi
ntpdate time.desy.de

echo "==============================================================================="
echo " Setup node done."
echo " Note: The ssh key for github access to the msk-jenkins-documentation user"
echo "       must be generated and added manually on first setup."
echo "==============================================================================="
