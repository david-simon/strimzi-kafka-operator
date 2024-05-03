#!/usr/bin/env bash

set -ex

script_dir="$(dirname "${0}")"
# going back to repository root
cd "${script_dir}/../.."

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk

export TESTCONTAINERS_RYUK_DISABLED=TRUE
export TESTCONTAINERS_CHECKS_DISABLE=TRUE

mvn -B -Dsurefire.rerunFailingTestsCount=5 -Dfailsafe.rerunFailingTestsCount=2 verify "${@}"
