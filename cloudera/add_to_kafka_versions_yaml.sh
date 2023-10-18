#!/usr/bin/env bash

set -ex

if [[ $# -lt 4 ]]; then
  echo "Usage: $0 <kafka version> <kafka scala version (e.g. 2.13)> <kafka tgz url> <zookeeper version>"
  exit 1
fi

script_dir="$(dirname "$(realpath "${0}")")"
repo_root="${script_dir}/.."

kafka_versions_yaml=${repo_root}/kafka-versions.yaml

export kafka_version="${1}"
kafka_scala_version="${2}"
export url="${3}"
export truncated_kafka_version="${kafka_version%.*}"
export zookeeper_version="${4}"

kafka_tgz_path="${repo_root}/docker-images/artifacts/binaries/kafka/archives/"
kafka_tgz="kafka_${kafka_scala_version}-${kafka_version}.tgz"
cd "${script_dir}"
mvn dependency:copy -Dartifact="org.apache.kafka:kafka_${kafka_scala_version}:${kafka_version}:tgz" -DoutputDirectory="${kafka_tgz_path}" -DuseBaseVersion=true
export checksum=$(sha512sum "${kafka_tgz_path}${kafka_tgz}" | cut -d' ' -f1)

# check if there's a default version, fails if not found
yq '.[] | select(.default == true)' -e "${kafka_versions_yaml}"

yq '
  (.[] | select(.default == true)) |=
    (
      .version = env(truncated_kafka_version) |
      .artifactVersion = env(kafka_version) |
      .url = env(url) |
      .checksum = env(checksum) |
      .zookeeper = env(zookeeper_version)
    )
' -i "${kafka_versions_yaml}"
