#!/usr/bin/env bash

if [[ $# -lt 1 || "${1}" != "unit" && "${1}" != "system" ]]; then
  echo "Usage: $0 <test type>"
  echo " test type: the type of tests to execute, can be 'unit' or 'system' only."
  exit 1
fi

set -x

test_type="${1}"

script_dir="$(dirname "${0}")"
# going back to repository root, later on the current dir will be attached as volume to the docker container
cd "${script_dir}/../.."

container_id=$(docker run --privileged -d -v "$(pwd)":/usr/src/project -v ~/.m2:/root/.m2 docker-private.infra.cloudera.com/cloudera_thirdparty/docker:25.0.1-dind)
docker exec "${container_id}" "/usr/src/project/cloudera/testing/env_setup.sh"
docker exec "${container_id}" "/usr/src/project/cloudera/testing/${test_type}_test_setup_and_run.sh" "${@:2}"
result_exit_code=$?
docker rm --force "${container_id}"

exit $result_exit_code
