#!/bin/bash

# run this script on mskbuildhost.desy.de (NOT mskbuildhost2!)

VMS_TO_BACKUP='msktools-fwdocu msktools-gitlab msktools-jenkins-fw msktools-jenkins-sw msktools-redmine'
BACKUP_TARGET='/msktools-backup'
RSYNC_ARGS='-ax --exclude=swap.img --delete --delete-before'


for vm in ${VMS_TO_BACKUP}; do
  rsync ${RSYNC_ARGS} "${vm}:/" "${BACKUP_TARGET}/${vm}/"
done
