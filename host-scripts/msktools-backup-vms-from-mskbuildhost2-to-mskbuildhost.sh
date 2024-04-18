#!/bin/bash

# run this script on mskbuildhost.desy.de (NOT mskbuildhost2!)

VMS_TO_BACKUP='msktools-fwdocu msktools-gitlab msktools-jenkins-fw msktools-jenkins-sw msktools-redmine msktools-oncallsummarytool msktools-oncallreport'
BACKUP_TARGET='/msktools-backup'
RSYNC_ARGS='-ax --exclude=swap.img --delete --delete-before'


for vm in ${VMS_TO_BACKUP}; do
  rsync ${RSYNC_ARGS} "${vm}:/" "${BACKUP_TARGET}/${vm}/"
done


rsync ${RSYNC_ARGS} "mskbuildhost2:/" "${BACKUP_TARGET}/mskbuildhost2-root/"
rsync ${RSYNC_ARGS} "mskbuildhost2:/var" "${BACKUP_TARGET}/mskbuildhost2-var/"