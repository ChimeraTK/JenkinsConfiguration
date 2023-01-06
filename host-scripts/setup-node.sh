#!/bin/bash

if [ `hostname` == "mskbuildhost" -o `hostname` == "xfelspare1" ]; then
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
  # on Ubuntu 16.04 we need to get a newer version of valgrind
  if [ "$DISTRIB_RELEASE" = "16.04" ]; then
    add-apt-repository -y ppa:hola-launchpad/valgrind
    apt-get update
  fi
  # java for Jenkins CLI
  apt-get install -y default-jre-headless
  # docker
  apt-get install -y docker.io
  # generic build tools
  apt-get install -y g++ valgrind cppcheck lcov doxygen procmail make git gdb
  apt-get install -y cmake
  apt-get install -y dkms
  # for ChimeraTK core libraries:
  apt-get install -y libboost-all-dev libxml++2.6-dev
  # for DOOCS:
  apt-get install -y libldap2-dev libzmq3-dev rpcbind
  # for EPICS:
  apt-get install -y libreadline-dev
  # for QtHardMon:
  apt-get install -y libqt4-dev qtbase5-dev
  # for Python bindings:
  apt-get install -y python2.7-dev python-numpy python3-dev python3-numpy
  # for python testing
  apt-get install -y python-pytest
  # for converting pytest results to xUnit
  apt-get install -y xsltproc
  # for Matlab:
  apt-get install -y libxmu6 csh libxrandr2
  # for the FirmwareProgrammer
  apt-get install -y libncurses5-dev
  # for projects which use dot graphs in doxygen
  apt-get install -y graphviz
  # clang
  apt-get install -y clang clang-tools clang-format-6.0
  # for llrfctrl
  apt-get install -y libhdf5-dev liblua5.2-dev
  # NFS for the shared workspace
  apt-get install -y nfs-common
  # Python Sphinx for documentation of python bindings
  apt-get install -y python-sphinx
  # for doocs-bam-server:
  apt-get install -y libgsl-dev
  # for the doocs-psm-ctrl-server
  apt-get install -y libssl-dev
  # for the amtfdbaccess library
  apt-get install -y libpqxx-dev
  # for the amtf_piezoacscan server
  apt-get install -y libfftw3-dev
  # NTP demon to prevent clock drifts
  apt-get install -y openntpd ntpdate
  # subversion e.g. for the llrfCtrl_config script (checking out the backup)
  apt-get install -y subversion
  # PIP for cw_llrf_scripts_utils
  apt-get install -y python3-pip
  # for config generator
  apt-get install -y python3-mako
  # linux kernel headers on Debian
  if [ "$DISTRIB_ID" = "Debian" ]; then
    apt-get install -y linux-headers-amd64
  fi
  # on Debian, execute /etc/rc.local at boot time
  if [ "$DISTRIB_ID" = "Debian" ]; then
    touch /etc/rc.local
    chmod +x /etc/rc.local
    if [ -z "`grep rc.local /etc/crontab`" ]; then
      echo "@reboot         root    /bin/bash -c /etc/rc.local" >> /etc/crontab
    fi
  fi
  # clean up old packages
  apt-get autoremove -y
elif [ "$DISTRIB_ID" = "openSUSE project" -o "$DISTRIB_ID" = "openSUSE Leap" -o "$DISTRIB_ID" = "openSUSE Tumbleweed" -o "$DISTRIB_ID" = "openSUSE" ]; then
  Jenkins_buildhosts="SUSEtumbleweed"
  # This has been tested with openSUSE leap 42.2 and Tumbleweed
  zypper refresh
  zypper up -y
  zypper dup -y --force-resolution
  # generic build tools
  zypper install -y gcc-c++ cmake valgrind cppcheck lcov doxygen procmail make git gdb
  zypper install -y chrpath
  # for ChimeraTK core libraries:
  zypper install -y libboost_*-devel
  zypper install -y libxml++26-devel
  # for QtHardMon:
  zypper install -y libqt4-devel libqt5-qtbase-devel
  # for Python bindings:
  zypper install -y python-devel python-numpy-devel python-numpy python3-devel python3-numpy-devel
  # for the FirmwareProgrammer
  zypper install -y ncurses-devel
  # clang
  zypper install -y clang clang-checker llvm-clang
  # Python Sphinx for documentation of python bindings
  zypper install -y python-Sphinx
  # java for Jenkins CLI
  zypper install -y java-1_8_0-openjdk
  # NTP demon to prevent clock drifts
  zypper install -y ntp
  # for projects which use dot graphs in doxygen
  zypper install -y graphviz
  # For building kernel modules (mtcadummy)
  zypper install -y kernel-devel
  # For ApplicationCore
  zypper install -y hdf5-devel
  # execute /etc/rc.local at boot time
  touch /etc/rc.local
  chmod +x /etc/rc.local
  if [ -z "`grep rc.local /etc/crontab`" ]; then
    echo "@reboot         root    /bin/bash -c /etc/rc.local" >> /etc/crontab
  fi
elif [ "$DISTRIB_ID" = "Fedora" ]; then
  Jenkins_buildhosts="Fedora"
  # This has been tested with Fedora 28
  dnf -y update
  # generic build tools
  dnf install -y gcc-c++ cmake valgrind cppcheck lcov doxygen procmail make git gdb
  # for ChimeraTK core libraries:
  dnf install -y libboost_*-devel
  dnf install -y libxml++26-devel
  # for QtHardMon:
  dnf install -y libqt4-devel libqt5-qtbase-devel
  # for Python bindings:
  dnf install -y python-devel python-numpy-devel python-numpy python3-devel python3-numpy-devel
  # for the FirmwareProgrammer
  dnf install -y ncurses-devel
  # clang
  dnf install -y clang clang-checker llvm-clang
  # Python Sphinx for documentation of python bindings
  dnf install -y python-Sphinx
  # java for Jenkins CLI
  dnf install -y java-1_8_0-openjdk
  # NTP demon to prevent clock drifts
  dnf install -y ntp
  # for projects which use dot graphs in doxygen
  dnf install -y graphviz
  # For building kernel modules (mtcadummy)
  dnf install -y kernel-devel
  # For ApplicationCore
  dnf install -y hdf5-devel
  # execute /etc/rc.local at boot time
  touch /etc/rc.local
  chmod +x /etc/rc.local
  if [ -z "`grep rc.local /etc/crontab`" ]; then
    echo "@reboot         root    /bin/bash -c /etc/rc.local" >> /etc/crontab
  fi
elif [ "$DISTRIB_ID" = "Sabayon" ]; then
  # accept all licenses known to the system (to avoid questions)
  export ACCEPT_LICENSE="*"
  # update entropy package manager
  equo up
  equo u
  # small bash function: only install package if not yet installed
  function equo_install {
    while [ -n "$1" ]; do
      equo query installed "$1" > /dev/null || equo install "$1"
      shift
    done
  }
  # update portage
  equo_install sys-apps/portage
  emerge --sync
  # some required system tools
  equo_install sys-process/cronie app-admin/syslog-ng sys-kernel/linux-sabayon sys-apps/mlocate
  systemctl enable cronie
  systemctl start cronie
  systemctl enable syslog-ng
  systemctl start syslog-ng
  # generic build tools
  equo_install sys-devel/gcc dev-util/cmake dev-util/valgrind dev-util/cppcheck app-doc/doxygen mail-filter/procmail sys-devel/make dev-vcs/git
  . /etc/profile
  # for ChimeraTK core libraries
  equo_install dev-libs/boost dev-cpp/libxmlpp
  # for QtHardMon:
  equo_install dev-qt/qtcore-4
  # for Python bindings
  equo_install dev-python/numpy dev-python/sphinx
  # for the FirmwareProgrammer
  equo_install sys-libs/ncurses
  # java for Jenkins CLI
  equo_install virtual/jre
  # NTP demon to prevent clock drifts
  equo_install net-misc/ntp
  # For building kernel modules (mtcadummy)
  equo_install sys-kernel/sabayon-sources
  # install/update latest gcc and clang. We also need to re-install libc, libcxx, glib and libxml++ due to changed C++11 ABI in gcc-5 and 6 w.r.t gcc-4
  GCC_VERSION=`gcc --version | head -n1 | sed -e 's|^.* ||' -e 's|\..*$||'`
  if [ "${GCC_VERSION}" != 6 ]; then
    sed -i -e 's|^ACCEPT_KEYWORDS=.*$|ACCEPT_KEYWORDS="~amd64 **"|' /etc/portage/make.conf
    emerge --autounmask-write -v \>=sys-devel/gcc-6 dev-libs/glib =dev-cpp/libxmlpp-2.40.1 sys-libs/glibc sys-libs/libcxx sys-devel/clang
  else
    emerge -vu --autounmask-write gcc dev-libs/glib =dev-cpp/libxmlpp-2.40.1 sys-libs/glibc sys-libs/libcxx sys-devel/clang
  fi
  # switch to latest gcc
  gcc-config -l
  GCC_CONFIG_NUMBER=`gcc-config -l | wc -l`
  gcc-config ${GCC_CONFIG_NUMBER}
  gcc-config -l
  . /etc/profile
  # execute /etc/rc.local at boot time
  if [ -z "`grep rc.local /etc/crontab`" ]; then
    echo "@reboot         root    /bin/bash -c /etc/rc.local" >> /etc/crontab
  fi
else
  echo "Unknown distribution, cannot proceed!"
  exit 1
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

if ! modprobe mtcadummy > /dev/null 2>&1; then
  echo "Install mtcadummy driver..."
  rsync -av /common/pciedummy-driver/ /root/pciedummuy-driver
  cd /root/pciedummuy-driver
  make install
  modprobe mtcadummy
fi

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

if [ -z "`grep /home/msk_jenkins/workspace/mtca4u_systemlike_installation/${Jenkins_buildhosts}/Release/bin /etc/environment`" ]; then
  echo "Add /home/msk_jenkins/workspace/mtca4u_systemlike_installation/${Jenkins_buildhosts}/Release/bin to the PATH of msk_jenkins user..."
  # this must be in /etc/environment, because Matlab's ssh does not use /etc/profile???
  sed -i /etc/environment -e 's#\(PATH=".*\)"#\1:/home/msk_jenkins/workspace/mtca4u_systemlike_installation/'${Jenkins_buildhosts}'/Release/bin"#'
fi

echo "Mount /common at boot and run this script..."
cat <<EOF > /etc/rc.local
#!/bin/sh -e
#
# Warning: This script is overwritten at boot and every day by setup-node.sh
# Do not modify here!
#
mount /common /common -t 9p -o trans=virtio,version=9p2000.L
/common/setup-node.sh
exit 0
EOF

echo "Allow realtime priority for all users (used for performance tests)..."
if [ -z "`grep '* - rtprio 99' /etc/security/limits.conf`" ]; then
  echo "* - rtprio 99" >> /etc/security/limits.conf
fi

echo "Run this script every day via cron..."
cat <<EOF > /etc/cron.daily/setup-node.sh
#!/bin/bash -e
#
# Warning: This script is overwritten at boot and every day by setup-node.sh
# Do not modify here!
#
/common/setup-node.sh
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

if [ -z "`grep msk-jenkins-documentation /home/msk_jenkins/.git-credentials`" ]; then
  echo "Adding git credentials for documentation update..."
  echo "https://msk-jenkins-documentation:2e303954a32cf27593d4eb22e731dc4212a25ae1@github.com" > /home/msk_jenkins/.git-credentials
  chown msk_jenkins:msk_jenkins /home/msk_jenkins/.git-credentials
  chmod go-rwx /home/msk_jenkins/.git-credentials
fi

if [ -z "`grep xfelproxy.desy.de /etc/wgetrc`" ]; then
  echo "Setting up xfel proxy for wget..."
  echo "https_proxy = http://xfelproxy.desy.de:3128/" >> /etc/wgetrc
  echo "http_proxy = http://xfelproxy.desy.de:3128/" >> /etc/wgetrc
fi

if [ ! -f "/home/msk_jenkins/.gitconfig" ]; then
  echo "Setting up git config..."
cat > /home/msk_jenkins/.gitconfig <<EOF
[http]
        proxy = xfelproxy.desy.de:3128
        sslVerify = false

[push]
        default = simple
[user]
        email = msk_jenkins@msk-ubuntu1604.desy.de
        name = Automated MSK Jenkins User
EOF
fi

if [ -z "`grep /export/LOCALHOST/opt/msk-workspace /etc/fstab`" ]; then
  echo "Setting up NFS mount for workspace..."
  echo "xfelspare1.desy.de:/export/LOCALHOST/opt/msk-workspace    /workspace    nfs    rw    0 0" >> /etc/fstab
  mkdir -p /workspace
  mount /workspace
  mkdir -p /home/msk_jenkins/workspace
  chown msk_jenkins:msk_jenkins /home/msk_jenkins/workspace
  ln -sfn /workspace/mtca4u_systemlike_installation /home/msk_jenkins/workspace
  ln -sfn /workspace/DOOCS_installation /home/msk_jenkins/workspace
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
