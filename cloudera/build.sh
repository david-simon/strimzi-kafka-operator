#!/usr/bin/env bash

set -ex

set -a
. cloudera/build_vars.properties
set +a

env

# set the default registry, repository/org and tag in helm-chart
# replacing in ./packaging/helm-charts/helm3/strimzi-kafka-operator/README.md too
chart_values="./packaging/helm-charts/helm3/strimzi-kafka-operator/values.yaml"
readme="./packaging/helm-charts/helm3/strimzi-kafka-operator/README.md"
sed -i "s/defaultImageRegistry:.*/defaultImageRegistry: ${DOCKER_REGISTRY}/g" "${chart_values}"
sed -i "s/\(defaultImageRegistry.*\)\`.*\`/\1\`${DOCKER_REGISTRY}\`/g" "${readme}"
sed -i "s/defaultImageRepository:.*/defaultImageRepository: ${DOCKER_ORG}/g" "${chart_values}"
sed -i "s/\(defaultImageRepository.*\)\`.*\`/\1\`${DOCKER_ORG}\`/g" "${readme}"
sed -i "s/defaultImageTag:.*/defaultImageTag: ${DOCKER_TAG}/g" "${chart_values}"
sed -i "s/\(defaultImageTag.*\)\`.*\`/\1\`${DOCKER_TAG}\`/g" "${readme}"

# updating examples
kafka_version=$(yq '.[] | select(.default == true) | .version' kafka-versions.yaml)
find packaging/examples -name '*.yaml' -exec yq -i "with(select(.spec.version);.spec.version=\"${kafka_version}\") | with(select(.spec.kafka.version);.spec.kafka.version=\"${kafka_version}\")" {} \;

# release target requires modifications done by the 'all' target but it doesn't set the maven project version
make release_prepare release_version release_helm_version release_maven

backup_RELEASE_VERSION="${RELEASE_VERSION}"
# Unset RELEASE_VERSION in order to let make all pick it up from the release.version file
unset RELEASE_VERSION

targets="${BUILD_MAKE_TARGET:-all java_install}"
for target in ${targets}
do
  make "${target}"
done

# passing RELEASE_VERSION for the release target
export RELEASE_VERSION="${backup_RELEASE_VERSION}"
make release

# rename helm tgz to the same what in Chart.yaml + version
mv strimzi-kafka-operator-helm-3-chart-${CHART_SEMANTIC_RELEASE_VERSION}.tgz strimzi-kafka-operator-${CHART_SEMANTIC_RELEASE_VERSION}.tgz

# create examples tar.gz
mkdir "examples-${RELEASE_VERSION}"
cp -rv "strimzi-${RELEASE_VERSION}"/examples/* "examples-${RELEASE_VERSION}"
tar czf "examples-${RELEASE_VERSION}.tar.gz" "examples-${RELEASE_VERSION}"
