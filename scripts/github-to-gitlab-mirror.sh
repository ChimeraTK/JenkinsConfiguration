#!/bin/bash -e
# shellcheck disable=SC2016
REPOLIST=$( gh repo list ChimeraTK -L 300 | grep '^ChimeraTK/' | sed -e 's_^ChimeraTK/__' -e 's_[[:space:]].*$__' )

export GITLAB_HOST=gitlab.desy.de

NMIRRORS=0
ERROR=0

for repo in $REPOLIST; do

  NMIRRORS=$(( NMIRRORS + 1 ))

  echo "=== Mirroring $repo"
  if [[ "${repo}" == "chimeratk.github.io" ]] || [[ "${repo}" == "gcc-13-on-bookworm" ]]; then
    echo "(Skipping, big and easy to recreate so no backup necessary)"
    continue
  fi


  mirrorname="${GITLAB_HOST}/chimeratk-mirror/${repo}"
  if ! glab repo view "${mirrorname}" > /dev/null 2>&1 ; then
    rm -rf temp-checkout
    mkdir temp-checkout
    cd temp-checkout
    git init .
    NO_PROMPT=1 glab repo create -P "${mirrorname}"
    cd ..
  fi

  rm -rf temp-checkout
  mkdir temp-checkout
  cd temp-checkout

  git init --bare
  git remote add origin --mirror=fetch "https://github.com/ChimeraTK/$repo.git"
  git remote add gitlab "https://${mirrorname}.git"

  # Configure which refs to fetch, to exclude GitHub pull requests. Including them would lead to an error message
  # when trying to push the pull request refs to GitLab ("deny updating a hidden ref").
  FETCH='\tfetch = +refs/heads/*:refs/heads/*\n\tfetch = +refs/tags/*:refs/tags/*'
  # shellcheck disable=SC2016
  sed -i config -e 's_^[[:space:]]*fetch = +refs/\*:refs/\*$_'"$FETCH"'_'

  git remote update

  # Make sure the master branch is protected, just in case a previous run of this script was interrupted in the wrong place
  glab api "projects/chimeratk-mirror%2F$repo/protected_branches/master" -F allow_force_push=false -X PATCH

  PUSHFAIL=0
  # The following 2 lines are similar to "git push --mirror gitlab" but do not delete anything
  git push gitlab --all --force || PUSHFAIL=1
  git push gitlab --tags --force || PUSHFAIL=1

  if [ "$PUSHFAIL" != "0" ]; then
    # Pushing to protected master might fail if upstream had force-pushed changes to master

    # First create a backup of the previous master branch with a new, unique name and protect it
    MASTER_BACKUP_NAME="master-$( date +%Y-%m-%d_%H.%M.%S )"
    git branch "$MASTER_BACKUP_NAME" gitlab/master
    git push gitlab "$MASTER_BACKUP_NAME"

    # Now unprotect the master branch, retry push and finally protect the branch again
    glab api "projects/chimeratk-mirror%2F$repo/protected_branches/master" -F allow_force_push=true -X PATCH

    PUSHFAIL=0
    git push gitlab --all --force || PUSHFAIL=1
    git push gitlab --tags --force || PUSHFAIL=1

    glab api "projects/chimeratk-mirror%2F$repo/protected_branches/master" -F allow_force_push=false -X PATCH

    # Protect the backed-up previous master branch
    glab api "projects/chimeratk-mirror%2F$repo/protected_branches/" -F "name=$MASTER_BACKUP_NAME" -F allow_force_push=false -X POST

    # Check for error during push
    if [ "$PUSHFAIL" != "0" ]; then
      echo "*** ERROR: Pushing still failed. Continue with other repos and fail later."
      ERROR=1
    fi
  fi

  cd ..
  rm -rf temp-checkout

done

if [ $NMIRRORS -lt 10 ]; then

  echo "Only $NMIRRORS repos have been mirrored. Something probably went wrong..."
  exit 1

fi

if [ "$ERROR" != "0" ]; then
  echo "Errors have occurred before!"
  exit 1
fi
