#!/usr/bin/env bash

set -e

FIND="find"
if [[ $(uname -s) == "Darwin" ]]; then
  FIND="gfind"
fi

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <strimzi_version>"
  exit 1
fi

blacklist=(
  "bridge"
  "connect"
  "mirror-maker"
  "kraft"
)

echo "Deleting examples that match the following patterns:" "${blacklist[@]}"
for pattern in "${blacklist[@]}" ; do
    $FIND "./strimzi-$1/examples" -regextype posix-extended -regex ".*$pattern.*" -print -delete
done

$FIND "./strimzi-$1/examples" -depth -type d -empty -print -delete