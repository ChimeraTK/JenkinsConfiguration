#!/bin/bash

VMNET=virbr1
DESYNET=eno1

# reset everything
iptables --flush
iptables --table nat --flush
iptables --delete-chain
iptables --table nat --delete-chain

# enable NAT
iptables --table nat --append POSTROUTING --out-interface $DESYNET -j MASQUERADE
iptables --append FORWARD --in-interface $VMNET -j ACCEPT

echo 1 > /proc/sys/net/ipv4/ip_forward


# servers running on the headnode (mskbuildhost) which should be reachable from the DESY network
iptables -A INPUT -i $DESYNET --protocol tcp --dport ssh      -j ACCEPT
iptables -A INPUT -i $DESYNET --protocol tcp --dport https    -j ACCEPT
iptables -A INPUT -i $DESYNET --protocol tcp --dport 6080     -j ACCEPT
iptables -A INPUT -i $DESYNET --protocol tcp --dport 111      -j ACCEPT
iptables -A INPUT -i $DESYNET --protocol tcp --dport 2049     -j ACCEPT

# allow ping
iptables -A INPUT -i $DESYNET --protocol icmp    -j ACCEPT
iptables -A INPUT -i $VMNET --protocol icmp    -j ACCEPT

# forward ssh to VMs
for((i=20; i<40; i++)); do
  iptables -A PREROUTING -t nat -i $DESYNET -p tcp -s mskllrfredminesrv.desy.de --dport 220$i -j DNAT --to-destination 192.168.100.$i:22
  iptables -A FORWARD -p tcp -d 192.168.100.$i --dport 22 -j ACCEPT
done

# block other external incoming traffic
iptables -A INPUT -i $DESYNET -m state --state NEW,INVALID    -j DROP
