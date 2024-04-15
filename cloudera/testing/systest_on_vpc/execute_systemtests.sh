#!/usr/bin/env bash

# This script is designed to run on:
# AMI: ami-06b70f26f2cc9db14
# AMI Name: cloudera-systest-base-ubuntu-20.04-hvm

set -ex

script_dir=$(dirname "$(realpath "${0}")")

if [[ -z "${GBN}" ]]; then
  GBN="${REL_CSM_OPERATOR_VERSION#*//}"
fi

if [[ -z "${GBN}" ]]; then
  echo "GBN was not received, aborting"
  exit 1
fi

parallelization_factor="${PARALLELIZATION_FACTOR:-1}"
parallelization_bucket_number="${BUCKET_NUMBER:-1}"
additional_systemtest_profiles=""

profiles="${1:-sanity}"
additional_args="${2}"

gbn_url="https://cloudera-build-us-west-1.vpc.cloudera.com/s3/build/${GBN}"

build_json="build.json"
build_json_url="${gbn_url}/${build_json}"
build_json_file="${script_dir}/${build_json}"
curl -sLo "${build_json_file}" "${build_json_url}"

docker_images_info_json=docker_images_info.json
docker_images_info_json_url="${gbn_url}/json_files/images/strimzi/${docker_images_info_json}"
docker_images_info_json_file="${script_dir}/${docker_images_info_json}"
curl -sLo "${docker_images_info_json_file}" "${docker_images_info_json_url}"

gbn_csm_operator_artifacts_base_url="${gbn_url}/csm-operator/1.x"
gbn_maven_repo="${gbn_csm_operator_artifacts_base_url}/maven-repository"

versions_json="versions.json"
gbn_versions_json_url="${gbn_maven_repo}/${versions_json}"
versions_json_file="${script_dir}/${versions_json}"
curl -sLo "${versions_json_file}" "${gbn_versions_json_url}"

function command_exists {
  command -v "$@" > /dev/null 2>&1
}

function setup_env {
  apt update
  apt install curl jq make maven openjdk-17-jdk -y

  if ! command_exists docker; then
    # install docker
    curl -fsSL https://get.docker.com -o get-docker.sh
    sh get-docker.sh
  fi

  if ! command_exists yq; then
    # install yq
    curl -sLO "https://cloudera-build-us-west-1.vpc.cloudera.com/s3/ARTIFACTS/DIM-QE/strimziTesting/yq_linux_amd64"
    install yq_linux_amd64 /usr/local/bin/yq
    rm yq_linux_amd64
  fi

  if ! command_exists helm; then
    # install helm
    curl -sLO "https://cloudera-build-us-west-1.vpc.cloudera.com/s3/ARTIFACTS/DIM-QE/SysTestDockerBuildDependencies/csm-operator/helm/3.13.2/helm-v3.13.2-linux-amd64.tar.gz"
    tar xzfv helm-v3.13.2-linux-amd64.tar.gz --directory=/usr/local/bin/ --strip-components=1 linux-amd64/helm
    rm helm-v3.13.2-linux-amd64.tar.gz
  fi

  if ! command_exists kubectl; then
    # install kubectl
    curl -sLO "https://cloudera-build-us-west-1.vpc.cloudera.com/s3/ARTIFACTS/DIM-QE/SysTestDockerBuildDependencies/csm-operator/kubectl/1.28.4/kubectl"
    install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
    rm kubectl
  fi

  if ! command_exists minikube; then
    # install minikube
    curl -sLO "https://cloudera-build-us-west-1.vpc.cloudera.com/s3/ARTIFACTS/DIM-QE/strimziTesting/minikube-linux-amd64"
    install minikube-linux-amd64 /usr/local/bin/minikube
    rm minikube-linux-amd64
  fi

  # run a registry for minikube
  cloudera_thirdparty="docker-private.infra.cloudera.com/cloudera_thirdparty"
  cloudera_base="docker-private.infra.cloudera.com/cloudera_base"
  registry_image="${cloudera_thirdparty}/registry:2.8.3"
  docker container ls | grep registry || docker run -d -p 5000:5000 "${registry_image}"

  MINIKUBE_MEMORY=$(free -m | grep "Mem" | awk '{print $2}')
  MINIKUBE_CPU=$(awk '$1~/cpu[0-9]/{usage=($2+$4)*100/($2+$4+$5); print $1": "usage"%"}' /proc/stat | wc -l)

  export MINIKUBE_WANTUPDATENOTIFICATION=false
  export MINIKUBE_WANTREPORTERRORPROMPT=false
  export MINIKUBE_HOME=$HOME
  export CHANGE_MINIKUBE_NONE_USER=true

  if [[ ! -f minikube_cache.tar.gz ]]; then
    curl -sLO "https://cloudera-build-us-west-1.vpc.cloudera.com/s3/ARTIFACTS/DIM-QE/strimziTesting/minikube_cache.tar.gz"
    tar xzf minikube_cache.tar.gz
  fi
  minikube status || minikube start --extra-config=apiserver.authorization-mode=Node,RBAC \
    --insecure-registry=localhost:5000 \
    --base-image=docker-private.infra.cloudera.com/cloudera_thirdparty/k8s-minikube/kicbase:v0.0.42 \
    --cpus="${MINIKUBE_CPU}" --memory="${MINIKUBE_MEMORY}" --force
  minikube addons enable default-storageclass
  minikube addons enable registry --images="Registry=${registry_image},KubeRegistryProxy=${cloudera_thirdparty}/k8s-minikube/kube-registry-proxy:0.0.5"
  minikube addons enable registry-aliases --images="CoreDNSPatcher=${cloudera_thirdparty}/rhdevelopers/core-dns-patcher:latest,Alpine=${cloudera_base}/alpine:3.11.2,Pause=${cloudera_thirdparty}/google_containers/pause:3.1"

  if ! kubectl get clusterrolebinding add-on-cluster-admin; then
    kubectl create clusterrolebinding add-on-cluster-admin --clusterrole=cluster-admin --serviceaccount=kube-system:default
  fi

  for nodeName in $(kubectl get nodes -o custom-columns=:.metadata.name --no-headers);
  do
    echo "${nodeName}";
    kubectl label node "${nodeName}" rack-key=zone;
  done 
}

function checkout_strimzi {
  [[ -d strimzi-kafka-operator ]] && rm -fr strimzi-kafka-operator
  git clone https://github.infra.cloudera.com/CDF/strimzi-kafka-operator.git
  cd strimzi-kafka-operator

  pr_ref="$(jq -r '.review_ref // empty | select(. | contains("strimzi-kafka-operator")) | split(":") [2]' "${build_json_file}")"
  source_ref="$(jq -r '.sources[] | select(.repo | endswith("strimzi-kafka-operator.git")) | .ref' "${build_json_file}")"
  ref_to_checkout="${pr_ref:-${source_ref:-"csm-operator-main"}}"

  git fetch origin "${ref_to_checkout}" && git checkout FETCH_HEAD
}

function setup_docker_env_vars {
  export DOCKER_REGISTRY="$(jq -r '.images.operator.dev.image_url | split("/") [0]' "${docker_images_info_json_file}")"
  export DOCKER_ORG="$(jq -r '.images.operator.dev.image_url | split("/") [1]' "${docker_images_info_json_file}")"
  export DOCKER_TAG="$(jq -r '.images.operator.dev.image_url | split(":") [1]' "${docker_images_info_json_file}")"
}

function helm_adjustment {
  # adjust packaging/install stuff via the helm_install goal
  cd "${script_dir}/strimzi-kafka-operator"
  chart_values="./packaging/helm-charts/helm3/strimzi-kafka-operator/values.yaml"
  sed -i "s/defaultImageRegistry:.*/defaultImageRegistry: ${DOCKER_REGISTRY}/g" "${chart_values}"
  sed -i "s/defaultImageRepository:.*/defaultImageRepository: ${DOCKER_ORG}/g" "${chart_values}"
  sed -i "s/defaultImageTag:.*/defaultImageTag: ${DOCKER_TAG}/g" "${chart_values}"
  make helm_install
}

function maven_version_setup {
  export MAVEN_ARGS="-B"
  RELEASE_VERSION="${DOCKER_TAG}" make release_maven
}

function configure_systemtest {
  CLUSTER_OPERATOR_INSTALL_TYPE="bundle"
  STRIMZI_FEATURE_GATES=""
  STRIMZI_RBAC_SCOPE="CLUSTER"

  if [[ ${parallelization_factor} -gt 1 ]]; then
    # the following groups of profiles containing the profiles which could be executed together (no matter what kind of pairing).
    # ideally, when para fact is 11 (# of elems in both array) each bucket receives one profile from these groups
    test_profiles=("acceptance" "azp_kafka_oauth" "azp_security" "azp_dynconfig_listeners_tracing_watcher" "azp_operators" "azp_remaining")
    # STRIMZI_FEATURE_GATES env var is set for these profiles
    feature_gates_test_profiles=("azp_kafka_oauth" "azp_security" "azp_dynconfig_listeners_tracing_watcher" "azp_operators" "azp_remaining")

    # splitting the buckets into two pieces/groups: regular and the feature gates test
    # first group of profiles is bigger so it'll be split into more
    (( all_profiles_cnt = ${#test_profiles[@]} + ${#feature_gates_test_profiles[@]} ))
    (( feature_gates_test_profiles_chunks = ( parallelization_factor * ${#feature_gates_test_profiles[@]} ) / all_profiles_cnt ))
    (( test_profiles_chunks = parallelization_factor - feature_gates_test_profiles_chunks ))

    # splitting an array into N pieces and returning one specific split, converted to comma separated string 
    # $1: number of pieces the array should be split into
    # $2: index of the split we want
    # rest of the args are the array elements
    function get_profiles {
      num_chunks=${1}
      shift
      chunk_idx=${1}
      shift
      array=("${@}")

      comma_separated_elems=""
      for (( i=chunk_idx; i < ${#array[@]}; i+=num_chunks )); do
        [[ -n "${comma_separated_elems}" ]] && comma_separated_elems+=","
        comma_separated_elems+="${array[i]}"
      done
      echo "${comma_separated_elems}"
    }

    if [[ ${parallelization_bucket_number} -le ${test_profiles_chunks} ]]; then
      additional_systemtest_profiles=$(get_profiles "${test_profiles_chunks}" "$(( parallelization_bucket_number - 1 ))" "${test_profiles[@]}")
    else
      additional_systemtest_profiles=$(get_profiles "${feature_gates_test_profiles_chunks}" "$(( parallelization_bucket_number - test_profiles_chunks - 1 ))" "${feature_gates_test_profiles[@]}")
      # should be whatever is set in strimzi_feature_gates config in .azure/templates/jobs/system-tests/feature_gates_regression_jobs.yaml
      STRIMZI_FEATURE_GATES='-KafkaNodePools,-UnidirectionalTopicOperator,-UseKRaft'
    fi
    # in case $additional_systemtest_profiles is empty here it means the parallelzation factor is higher than we can make use of, exiting
    if [[ -z "${additional_systemtest_profiles}" ]]; then
      echo "Parallelzation factor is higher than we can make use of, exiting"
      exit 255
    fi
  else
    # when the para fact is 1, the default profile is sanity, but can be overridden via ADDITIONAL_PROFILES env var
    additional_systemtest_profiles=${ADDITIONAL_PROFILES-"sanity"}
  fi

  export CLUSTER_OPERATOR_INSTALL_TYPE
  export STRIMZI_FEATURE_GATES
  export STRIMZI_RBAC_SCOPE
}

function generate_config_model {
  # extract kafka_version
  kafka_version="$(jq -r ".kafka" "${versions_json_file}")"

  # replace kafka-versions.yaml
  kafka_versions_file="kafka-versions.yaml"
  kafka_versions_url="${gbn_csm_operator_artifacts_base_url}/redhat7/yum/tars/strimzi/${kafka_versions_file}"
  curl -sLo "${kafka_versions_file}" "${kafka_versions_url}"
  local mvn_args="-DskipTests -Dmaven.javadoc.skip=true -e -V -B -Dgbn.repo.url=${gbn_maven_repo} -Dkafka.version=${kafka_version}"
  MVN_ARGS="${mvn_args}" mvn package -pl config-model-generator ${mvn_args}
}

function run_systemtest {
  mvn -B -fae \
    -Dgbn.repo.url="${gbn_maven_repo}" \
    -Dkafka.version="${kafka_version}" \
    -Dfailsafe.rerunFailingTestsCount=5 \
    -DfailIfNoTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dskip.surefire.tests \
    verify \
    -pl systemtest -P"${profiles},${additional_systemtest_profiles}" \
    ${additional_args} \
  || return $? # preventing immediate exit
}

function execute_systemtest_and_collect_results {
  run_systemtest || ret_code=$?
  if [[ ${ret_code} -ne 0 ]]; then
    echo "Test execution failed."
  fi

  cd "${script_dir}"
  find strimzi-kafka-operator \
    -name 'surefire-reports' \
    -o -name 'failsafe-reports' \
    -o -path '*/target/logs/*' | tar czf results.tar.gz -T -

  exit ${ret_code}
}

setup_env
checkout_strimzi
setup_docker_env_vars
helm_adjustment
maven_version_setup
configure_systemtest
generate_config_model
execute_systemtest_and_collect_results
