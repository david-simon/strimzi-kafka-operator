#!/usr/bin/env bash

set -ex

script_dir="$(dirname "${0}")"
# going back to repository root
cd "${script_dir}/../.."

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk

export DOCKER_REGISTRY="${1:-docker-private.infra.cloudera.com}"
export DOCKER_ORG="${2:-cloudera}"
export DOCKER_TAG="${3:-0.38.0.1.0.0-b197}"
test_group="${4:-cloudera}"

# adjust packaging/install stuff via the helm_install goal
chart_values="./packaging/helm-charts/helm3/strimzi-kafka-operator/values.yaml"
sed -i "s/defaultImageRegistry:.*/defaultImageRegistry: ${DOCKER_REGISTRY}/g" "${chart_values}"
sed -i "s/defaultImageRepository:.*/defaultImageRepository: ${DOCKER_ORG}/g" "${chart_values}"
sed -i "s/defaultImageTag:.*/defaultImageTag: ${DOCKER_TAG}/g" "${chart_values}"
make helm_install

mvn -B -fae \
  -Dfailsafe.rerunFailingTestsCount=2 \
  -DfailIfNoTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dskip.surefire.tests \
  verify \
  -pl systemtest -P"${test_group}" "${@:5}"
