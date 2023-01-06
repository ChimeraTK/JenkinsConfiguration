#!/bin/bash -e
T_VM_DOWN="$1"
T_REBOOT="$2"
if [ -z "$T_VM_DOWN" -o -z "$T_REBOOT" ]; then
  T_VM_DOWN="03:30"
  T_REBOOT="04:00"
fi
echo "The mskbuildhost will be rebooted at ${T_REBOOT}, all VMs will be shutdown at ${T_VM_DOWN} before."
echo "Press ENTER to continue, Ctrl+C to abord."
read || exit 1
IPPREFIX="192.168.100."
IPLIST="20 21 23 24"
for i in $IPLIST ; do
  IP=${IPPREFIX}${i}
  echo "Sending shutdown command to IP ${IP}..."
  sudo ssh $IP shutdown -c || true
  sudo ssh $IP screen -mdS shutdown shutdown -h ${T_VM_DOWN}
done
sudo shutdown -c || true
sudo shutdown -r ${T_REBOOT}
