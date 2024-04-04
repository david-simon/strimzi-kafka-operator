#!/usr/bin/env bash

set -ex

function main() {
  # change dir to root of repo
  cd "$(dirname "${BASH_SOURCE[0]}")/.."

  parse_args "$@"
  fetch_component_versions

  strimzi_version=$(<release.version)
  new_strimzi_version="$strimzi_version.$csm_operator_version"

  set_build_vars
  patch_kafka_versions
  set_dependency_versions
  set_doc_versions
  set_example_versions
  set_test_versions

  make release RELEASE_VERSION="$new_strimzi_version"
  make helm_install RELEASE_VERSION="$new_strimzi_version"
  git clean -fd
}

function print_usage() {
  echo "Usage: $0 [-b BUILD_CONFIG_URL]"
}

function parse_args() {
  POSITIONAL_ARGS=()
  while [[ $# -gt 0 ]]; do
    case $1 in
      -h|--help)
        print_usage
        exit 0
        ;;
      -b|--build-config)
        BUILD_CONFIG=$2
        shift 2
        ;;
      -*)
        echo "Unknown option $1"
        exit 1
        ;;
      *)
        POSITIONAL_ARGS+=("$1")
        shift
        ;;
    esac
  done

  : "${BUILD_CONFIG:="https://github.infra.cloudera.com/raw/CDF/csm-operator/csm-operator-main/re-configs/csm-operator-base.json"}"
  set -- "${POSITIONAL_ARGS[@]}"
}

function fetch_component_versions() {
  curl -Lk "$BUILD_CONFIG" > csm-operator-base.json
  csm_operator_version=$(jq -r ".apache[\"csm-operator\"].version" csm-operator-base.json)-SNAPSHOT
  kafka_version=$(jq -r ".apache.kafka.version" csm-operator-base.json)
  cruise_control_version=$(jq -r ".apache[\"cruise-control\"].version" csm-operator-base.json)
  rm csm-operator-base.json
}

function set_test_versions() {
  $SED -i "s/LATEST_KAFKA_VERSION =.*/LATEST_KAFKA_VERSION = \"$kafka_version_default\";/g" "cluster-operator/src/test/java/io/strimzi/operator/cluster/KafkaVersionTestUtils.java"
  $SED -i "s/LATEST_ZOOKEEPER_VERSION =.*/LATEST_ZOOKEEPER_VERSION = \"$zookeeper_version\";/g" "cluster-operator/src/test/java/io/strimzi/operator/cluster/KafkaVersionTestUtils.java"
}

function set_doc_versions() {
  $SED -i "s/:DefaultKafkaVersion:.*/:DefaultKafkaVersion: $kafka_version/g" "./documentation/shared/attributes.adoc"
  $SED -i "s/:KafkaVersionLower:.*/:KafkaVersionLower: -/g" "./documentation/shared/attributes.adoc"
  $SED -i "s/:KafkaVersionHigher:.*/:KafkaVersionHigher: $kafka_version/g" "./documentation/shared/attributes.adoc"
}

function set_example_versions() {
  $FIND ./packaging/examples -name '*.yaml' -type f -exec "$SED" -i "s/$kafka_version_upstream_default/$kafka_version_default/g" {} \;
}

function set_build_vars() {
  $SED -i "s/DOCKER_TAG=.*/DOCKER_TAG=$new_strimzi_version/g" "cloudera/build_vars.properties"
  $SED -i "s/RELEASE_VERSION=.*/RELEASE_VERSION=$new_strimzi_version/g" "cloudera/build_vars.properties"
  $SED -i "s/CHART_SEMANTIC_RELEASE_VERSION=.*/CHART_SEMANTIC_RELEASE_VERSION=$csm_operator_version/g" "cloudera/build_vars.properties"
}

function set_dependency_versions() {
  zookeeper_version=$(yq "(.[] | select(.default == true)).zookeeper" kafka-versions.yaml)

  $SED -i "s/<kafka.version>[^<]*/<kafka.version>$kafka_version.$csm_operator_version/g" "pom.xml"
  $SED -i "s/<zookeeper.version>[^<]*/<zookeeper.version>$zookeeper_version/g" "pom.xml"

  ggrep -r -l "cruise-control.version"

  for file in $(ggrep -r -l "cruise-control.version" docker-images/) ; do
      $SED -i "s/<cruise-control.version>[^<]*/<cruise-control.version>$cruise_control_version.$csm_operator_version/g" "$file"
  done

}


# Copies the kafka-versions.yaml file from the csm-operator-main branch to the current working branch
# as kafka-versions-downstream.yaml, finds all versions in it that are not present in the current
# kafka-versions.yaml files and appends them at the end of the file.
#
# If the kafka version found in csm-operator-base.json is not present in the file then it is added.
# If there are also any entries with the 'checksum' field empty (meaning we have not released it yet),
# then they are removed.
function patch_kafka_versions() {
  local upstream_versions
  local downstream_versions
  local versions_to_add
  local version
  local long_kafka_version
  local short_kafka_version
  local truncated_kafka_version
  local zookeeper_version

  git show csm-operator-main^:kafka-versions.yaml > kafka-versions-downstream.yaml

  kafka_version_upstream_default=$(yq "(.[] | select(.default == true)).version" kafka-versions.yaml)
  upstream_versions=$(yq ".[].version" kafka-versions.yaml)
  downstream_versions=$(yq ".[].version" kafka-versions-downstream.yaml)
  versions_to_add=$(comm -13 <(echo "$upstream_versions") <(echo "$downstream_versions"))

  yq --inplace ".[] |= (.default = false | .supported = false)" kafka-versions.yaml

  for version in $versions_to_add ; do
      yq "[.[] | select(.version == \"$version\")]" kafka-versions-downstream.yaml \
        | yq eval-all --no-doc --inplace "." kafka-versions.yaml -
  done

  long_kafka_version="$kafka_version.$csm_operator_version"
  short_kafka_version="${kafka_version%.*}"
  truncated_kafka_version="${long_kafka_version%.*}"
  if [[ -z $(yq ".[] | select(.version == \"$truncated_kafka_version\")" kafka-versions.yaml) ]]; then
    zookeeper_version=$(yq "(.[] | select(.default == true)).zookeeper" kafka-versions.yaml)

    yq --inplace 'del(.[] | select(.artifactVersion | match("SNAPSHOT$")))' kafka-versions.yaml
    yq --inplace ".[].default = false" kafka-versions.yaml

    yq "
      . +=
        {
          \"version\": \"$truncated_kafka_version\",
          \"artifactVersion\": \"$long_kafka_version\",
          \"url\": \"https://nexus-private.hortonworks.com/nexus/content/groups/public/org/apache/kafka/kafka_2.13/$long_kafka_version/kafka_2.13-$long_kafka_version.tgz\",
          \"checksum\": \"\",
          \"zookeeper\": \"$zookeeper_version\",
          \"default\": true,
          \"supported\": true,
          \"format\": ${short_kafka_version},
          \"protocol\": ${short_kafka_version},
          \"metadata\": ${short_kafka_version},
          \"third-party-libs\": \"${short_kafka_version}.x\"
        }
    " -i kafka-versions.yaml
  fi

  kafka_version_default=$(yq "(.[] | select(.default == true)).version" kafka-versions.yaml)

  rm kafka-versions-downstream.yaml
}

SED="sed"
FIND="find"
if [[ $(uname -s) == "Darwin" ]]; then
  SED="gsed"
  FIND="gfind"
fi

main "$@"