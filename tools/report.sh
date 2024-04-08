#!/usr/bin/env bash
# Self contained Strimzi reporting tool.
set -Eeuo pipefail
if [[ $(uname -s) == "Darwin" ]]; then
  shopt -s expand_aliases
  alias echo="gecho"; alias grep="ggrep"; alias sed="gsed"; alias date="gdate"
fi

{ # this ensures that the entire script is downloaded #
KUBECTL_INSTALLED=false
OC_INSTALLED=false
KUBE_CLIENT="kubectl"
OUT_DIR=""
SECRETS_OPT="hidden"

# sed non-printable text delimiter
SD=$(echo -en "\001") && readonly SD
# sed sensitive information filter expression
SE="s${SD}^\(\s*.*\password\s*:\s*\).*${SD}\1\<hidden>'${SD}; s${SD}^\(\s*.*\.key\s*:\s*\).*${SD}\1\<hidden>${SD}" && readonly SE

error() {
  echo "$@" 1>&2 && exit 1
}

# bash version check
if [[ -z ${BASH_VERSINFO+x} ]]; then
  error "No bash version information available, aborting"
fi
if [[ "${BASH_VERSINFO[0]}" -lt 4 ]]; then
  error "You need bash version >= 4 to run the script"
fi

# kube client check
if [[ -x "$(command -v kubectl)" ]]; then
  KUBECTL_INSTALLED=true
else
  if [[ -x "$(command -v oc)" ]]; then
    OC_INSTALLED=true
    KUBE_CLIENT="oc"
  fi
fi
if [[ $OC_INSTALLED = false && $KUBECTL_INSTALLED = false ]]; then
  error "There is no kubectl or oc installed"
fi

# kube connectivity check
$KUBE_CLIENT version -o yaml --request-timeout=5s 1>/dev/null

readonly USAGE="
Usage: report.sh [options]

Optional:
  --secrets=(off|hidden|all)    Secret verbosity. Default is hidden, only the secret key will be reported.
  --out-dir=<string>            Script output directory.
"
OPTSPEC=":-:"
while getopts "$OPTSPEC" optchar; do
  case "${optchar}" in
    -)
      case "${OPTARG}" in
        out-dir=*)
          OUT_DIR=${OPTARG#*=}
          OUT_DIR=${OUT_DIR//\~/$HOME} && readonly OUT_DIR
          ;;
        secrets=*)
          SECRETS_OPT=${OPTARG#*=} && readonly SECRETS_OPT
          ;;
        *)
          error "$USAGE"
          ;;
      esac;;
  esac
done
shift $((OPTIND-1))

if [[ -z $OUT_DIR ]]; then
  OUT_DIR="$(mktemp -d)"
fi

if [[ "$SECRETS_OPT" != "all" && "$SECRETS_OPT" != "off" && "$SECRETS_OPT" != "hidden" ]]; then
  echo "Unknown secrets verbosity level. Use one of 'off', 'hidden' or 'all'."
  echo " 'all' - secret keys and data values are reported"
  echo " 'hidden' - secrets with only data keys are reported"
  echo " 'off' - secrets are not reported at all"
  echo "Default value is 'hidden'"
  error "$USAGE"
fi

RESOURCES=(
  "deployments"
  "statefulsets"
  "replicasets"
  "configmaps"
  "secrets"
  "services"
  "poddisruptionbudgets"
  "roles"
  "rolebindings"
  "networkpolicies"
  "pods"
  "persistentvolumeclaims"
  "ingresses"
  "routes"
)
readonly CLUSTER_RESOURCES=(
  "clusterroles"
  "clusterrolebindings"
)

if [[ "$SECRETS_OPT" == "off" ]]; then
  RESOURCES=("${RESOURCES[@]/secrets}") && readonly RESOURCES
fi

CRDS=$($KUBE_CLIENT get crd -l app=strimzi --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name) && readonly CRDS

get_nonnamespaced_yamls() {
  local type="$1"
  mkdir -p "$OUT_DIR"/reports/"$type"
  local resources && resources=$($KUBE_CLIENT get "$type" -l app=strimzi -o name)
  echo "$type"
  for res in $resources; do
    local resources && resources=$($KUBE_CLIENT get "$type" -l app=strimzi -o name)
    echo "    $res"
    res=$(echo "$res" | cut -d "/" -f 2)
    $KUBE_CLIENT get "$type" "$res" -o yaml | sed "$SE" > "$OUT_DIR"/reports/"$type"/"$res".yaml
  done
}

get_crds() {
  local location="$OUT_DIR"/reports/customresourcedefinitions

  echo "customresourcedefinitions"
  mkdir -p "$location"
  for CRD in $CRDS; do
    echo "    $CRD"
    $KUBE_CLIENT get crd "$CRD" -o yaml > "$location"/"$CRD".yaml
  done
}

get_custom_resources() {
  local namespace="$1"
  local location="$OUT_DIR"/reports/namespaces/"$namespace"/customresources

  local found_resources=0
  echo "        customresources"
  for CRD in $CRDS; do
    RES=$($KUBE_CLIENT get "$CRD" -n "$namespace" -o name | cut -d "/" -f 2)
    if [[ -n $RES ]]; then
      echo "            $CRD"
      local cr_dir="${CRD//.*/}"
      local cr_location="$location"/"$cr_dir"
      mkdir -p "$cr_location"
      for j in $RES; do
        RES=$(echo "$j" | cut -f 1 -d " ")
        $KUBE_CLIENT get "$CRD" -n "$namespace" "$RES" -o yaml > "$cr_location"/"$RES".yaml
        echo "                $RES"
      done
      found_resources=$((found_resources + 1))
    fi
  done

  if [[ found_resources -gt 0 ]]; then
    return 0
  else
    return 1
  fi
}

get_masked_secrets() {
  local namespace="$1"
  local cluster="$2"
  local location="$OUT_DIR"/reports/namespaces/"$namespace"/secrets
  echo "        secrets"
  mkdir -p "$location"
  local resources && resources=$($KUBE_CLIENT get secrets -l strimzi.io/cluster="$cluster" -o name -n "$namespace")
  for res in $resources; do
    local filename && filename=$(echo "$res" | cut -f 2 -d "/")
    echo "            $res"
    local secret && secret=$($KUBE_CLIENT get "$res" -o yaml -n "$namespace")
    if [[ "$SECRETS_OPT" == "all" ]]; then
      echo "$secret" > "$location"/"$filename".yaml
    else
      echo "$secret" | sed "$SE" > "$location"/"$filename".yaml
    fi
  done
}

get_namespaced_yamls() {
  local namespace="$1"
  local cluster="$2"
  local type="$3"
  local location="$OUT_DIR"/reports/namespaces/"$namespace"/kafka_clusters/"$cluster"/"$type"

  mkdir -p "$location"
  local resources
  resources=$($KUBE_CLIENT get "$type" -l strimzi.io/cluster="$cluster" -o name -n "$namespace" 2>/dev/null ||true)
#  resources="$resources $($KUBE_CLIENT get "$type" -l strimzi.io/cluster="$BRIDGE" -o name -n "$NAMESPACE" 2>/dev/null ||true)"
#  resources="$resources $($KUBE_CLIENT get "$type" -l strimzi.io/cluster="$CONNECT" -o name -n "$NAMESPACE" 2>/dev/null ||true)"
#  resources="$resources $($KUBE_CLIENT get "$type" -l strimzi.io/cluster="$MM2" -o name -n "$NAMESPACE" 2>/dev/null ||true)"
  echo "                $type"
  if [[ -n $resources ]]; then
    for res in $resources; do
      local filename && filename=$(echo "$res" | cut -f 2 -d "/")
      echo "                    $res"
      if [[ "$SECRETS_OPT" == "all" ]]; then
        $KUBE_CLIENT get "$res" -o yaml -n "$namespace" > "$location"/"$filename".yaml
      else
        $KUBE_CLIENT get "$res" -o yaml -n "$namespace" | sed "$SE" > "$location"/"$filename".yaml
      fi
    done
  fi
}

get_topic_describe() {
  local namespace="$1"
  local cluster="$2"
  local location="$OUT_DIR"/reports/namespaces/"$namespace"/kafka_clusters/"$cluster"/topic_describe

  echo "                describe topics"
  pod=$(set +o pipefail; $KUBE_CLIENT -n "$namespace" get po -l strimzi.io/component-type=kafka,strimzi.io/kind=Kafka,strimzi.io/name="$cluster-kafka" --ignore-not-found --no-headers -o jsonpath='{range .items[*]}{.status.containerStatuses[*].ready.true}{.metadata.name}{ "\n"}{end}' | head -n 1)
  if [[ -n "$pod" ]]; then
    mkdir -p "$location"

    # Shellcheck SC2016 is disabled because we want expressions to be expanded in the target pod, not in current shell
    # shellcheck disable=SC2016
    $KUBE_CLIENT -n "$namespace" exec "$pod" -- bash -c '# Extract variables from strimzi.properties && \
        listener=$(grep "control.plane.listener.name" /tmp/strimzi.properties | sed -e "s/control.plane.listener.name=//g") && \
        port=$(grep "control.plane.listener.name" /tmp/strimzi.properties | sed -e "s/.*-//g") && \
        bootstrapserver=$(grep "advertised.listeners" /tmp/strimzi.properties | sed -e "s/.*$listener:\/\/\(.*\):$port.*/\1/") && \
        # Create client config file && \
        grep -i "listener.name.$listener." /tmp/strimzi.properties | sed -e "s/listener.name.$listener.//gI" > /tmp/report-client-config.properties && \
        echo "security.protocol=ssl" >> /tmp/report-client-config.properties && \
        # Execute topic describe && \
        bin/kafka-topics.sh --describe --command-config=/tmp/report-client-config.properties --bootstrap-server $bootstrapserver:$port' \
        > "$location"/topic-describe.txt 2>/dev/null||true
    $KUBE_CLIENT -n "$namespace" exec "$pod" -- bash -c 'rm -rf /tmp/report-client-config.properties' 2>/dev/null||true

    echo "                    topic describe collected"
  else
    echo "                    topic describe failed due to no kafka pods available"
  fi
}

get_pod_logs() {
  local namespace="$1"
  local pod="$2"
  local location="$3"/logs
  local con="${4-}"

  if [[ -n $pod ]]; then
    local names && names=$($KUBE_CLIENT -n "$namespace" get po "$pod" -o jsonpath='{.spec.containers[*].name}' --ignore-not-found)
    local count && count=$(echo "$names" | wc -w)
    local logs
    mkdir -p "$location"
    if [[ "$count" -eq 1 ]]; then
      logs="$($KUBE_CLIENT -n "$namespace" logs "$pod" ||true)"
      if [[ -n $logs ]]; then printf "%s" "$logs" > "$location"/"$pod".log; fi
      logs="$($KUBE_CLIENT -n "$namespace" logs "$pod" -p 2>/dev/null ||true)"
      if [[ -n $logs ]]; then printf "%s" "$logs" > "$location"/"$pod".log.0; fi
    fi
    if [[ "$count" -gt 1 && -n "$con" && "$names" == *"$con"* ]]; then
      logs="$($KUBE_CLIENT -n "$namespace" logs "$pod" -c "$con" ||true)"
      if [[ -n $logs ]]; then printf "%s" "$logs" > "$location"/"$pod"-"$con".log; fi
      logs="$($KUBE_CLIENT -n "$namespace" logs "$pod" -p -c "$con" 2>/dev/null ||true)"
      if [[ -n $logs ]]; then printf "%s" "$logs" > "$location"/"$pod"-"$con".log.0; fi
    fi
  fi
}

get_cluster_operator_info() {
  local namespace="$1"
  local location="$OUT_DIR"/reports/namespaces/"$namespace"/cluster_operator

  local co_deploy
  co_deploy=$($KUBE_CLIENT get deploy strimzi-cluster-operator -o name -n "$namespace" --ignore-not-found)
  if [[ -n $co_deploy ]]; then
    echo "        clusteroperator"
    echo "            $co_deploy"
    mkdir -p "$location"/deployments
    co_deploy=$(echo "$co_deploy" | cut -d "/" -f 2) && readonly co_deploy
    $KUBE_CLIENT get deploy "$co_deploy" -o yaml -n "$namespace" > "$location"/deployments/"$co_deploy".yaml
    CO_RS=$($KUBE_CLIENT get rs -l strimzi.io/kind=cluster-operator -o name -n "$namespace" --ignore-not-found)
    if [[ -n $CO_RS ]]; then
      echo "            $CO_RS"
      mkdir -p "$location"/replicasets
      CO_RS=$(echo "$CO_RS" | cut -d "/" -f 2) && readonly CO_RS
      $KUBE_CLIENT get rs "$CO_RS" -o yaml -n "$namespace" > "$location"/replicasets/"$CO_RS".yaml
    fi
    mapfile -t CO_PODS < <($KUBE_CLIENT get po -l strimzi.io/kind=cluster-operator -o name -n "$namespace" --ignore-not-found)
    if [[ ${#CO_PODS[@]} -ne 0 ]]; then
      for pod in "${CO_PODS[@]}"; do
        echo "            $pod"
        mkdir -p "$location"/pods
        CO_POD=$(echo "$pod" | cut -d "/" -f 2)
        $KUBE_CLIENT get po "$CO_POD" -o yaml -n "$namespace" > "$location"/pods/"$CO_POD".yaml
        get_pod_logs "$namespace" "$CO_POD" "$location"
      done
    fi
    CO_CM=$($KUBE_CLIENT get cm strimzi-cluster-operator -o name -n "$namespace" --ignore-not-found)
    if [[ -n $CO_CM ]]; then
      echo "            $CO_CM"
      mkdir -p "$location"/configmaps
      CO_CM=$(echo "$CO_CM" | cut -d "/" -f 2) && readonly CO_CM
      $KUBE_CLIENT get cm "$CO_CM" -o yaml -n "$namespace" > "$location"/configmaps/"$CO_CM".yaml
    fi
    return 0
  else
    return 1
  fi
}

get_drain_cleaner_info() {
  local namespace="$1"
  local location="$OUT_DIR"/reports/namespaces/"$namespace"

  echo "        draincleaner"
  DC_DEPLOY=$($KUBE_CLIENT get deploy strimzi-drain-cleaner -o name -n "$namespace" --ignore-not-found) && readonly DC_DEPLOY
  if [[ -n $DC_DEPLOY ]]; then
    echo "            $DC_DEPLOY"
    $KUBE_CLIENT get deploy strimzi-drain-cleaner -o yaml -n "$namespace" > "$location"/deployments/drain-cleaner.yaml
    $KUBE_CLIENT get po -l app=strimzi-drain-cleaner -o yaml -n "$namespace" > "$location"/pods/drain-cleaner.yaml
    DC_POD=$($KUBE_CLIENT get po -l app=strimzi-drain-cleaner -o name -n "$namespace" --ignore-not-found)
    if [[ -n $DC_POD ]]; then
      echo "            $DC_POD"
      DC_POD=$(echo "$DC_POD" | cut -d "/" -f 2) && readonly DC_POD
      get_pod_logs "$namespace" "$DC_POD" "$location"
    fi
  fi
}

get_events() {
  local namespace="$1"
  local location="$OUT_DIR"/reports/namespaces/"$namespace"/events

  echo "            events"
  local events
  events=$($KUBE_CLIENT get event -n "$namespace" -o wide --ignore-not-found) && readonly events
  if [[ -n $events ]]; then
    mkdir -p "$location"
    echo "$events" > "$location"/events.txt
  fi
}

get_logs() {
  local namespace="$1"
  local cluster="$2"
  local location="$OUT_DIR"/reports/namespaces/"$namespace"/kafka_clusters/"$cluster"
  local configs_location="$location"/configs

  echo "                logs"
  mkdir -p "$configs_location"
  if [[ -n $cluster ]]; then
    mapfile -t KAFKA_PODS < <($KUBE_CLIENT -n "$namespace" get po -l strimzi.io/kind=Kafka,strimzi.io/name="$cluster-kafka" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name)
    if [[ ${#KAFKA_PODS[@]} -ne 0 ]]; then
      for pod in "${KAFKA_PODS[@]}"; do
        echo "                    $pod"
        get_pod_logs "$namespace" "$pod" "$location" kafka
        get_pod_logs "$namespace" "$pod" "$location" tls-sidecar # for old Strimzi releases
        $KUBE_CLIENT -n "$namespace" exec -i "$pod" -c kafka -- \
          cat /tmp/strimzi.properties > "$configs_location"/"$pod".cfg 2>/dev/null||true
      done
    fi
    mapfile -t ZOO_PODS < <($KUBE_CLIENT -n "$namespace" get po -l strimzi.io/kind=Kafka,strimzi.io/name="$cluster-zookeeper" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name)
    if [[ ${#ZOO_PODS[@]} -ne 0 ]]; then
      for pod in "${ZOO_PODS[@]}"; do
        echo "                    $pod"
        get_pod_logs "$namespace" "$pod" "$location" zookeeper
        get_pod_logs "$namespace" "$pod" "$location" tls-sidecar # for old Strimzi releases
        $KUBE_CLIENT exec -i "$pod" -n "$namespace" -c zookeeper -- \
          cat /tmp/zookeeper.properties > "$configs_location"/"$pod".cfg 2>/dev/null||true
      done
    fi
    mapfile -t ENTITY_PODS < <($KUBE_CLIENT -n "$namespace" get po -l strimzi.io/kind=Kafka,strimzi.io/name="$cluster-entity-operator" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name)
    if [[ ${#ENTITY_PODS[@]} -ne 0 ]]; then
      for pod in "${ENTITY_PODS[@]}"; do
        echo "                    $pod"
        get_pod_logs "$namespace" "$pod" "$location" topic-operator
        get_pod_logs "$namespace" "$pod" "$location" user-operator
        get_pod_logs "$namespace" "$pod" "$location" tls-sidecar
      done
    fi
    mapfile -t CC_PODS < <($KUBE_CLIENT -n "$namespace" get po -l strimzi.io/kind=Kafka,strimzi.io/name="$cluster-cruise-control" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name)
    if [[ ${#CC_PODS[@]} -ne 0 ]]; then
      for pod in "${CC_PODS[@]}"; do
        echo "                    $pod"
        get_pod_logs "$namespace" "$pod" "$location"
        get_pod_logs "$namespace" "$pod" "$location" tls-sidecar # for old Strimzi releases
      done
    fi
    mapfile -t EXPORTER_PODS < <($KUBE_CLIENT -n "$namespace" get po -l strimzi.io/kind=Kafka,strimzi.io/name="$cluster-kafka-exporter" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name)
    if [[ ${#EXPORTER_PODS[@]} -ne 0 ]]; then
      for pod in "${EXPORTER_PODS[@]}"; do
        echo "                    $pod"
        get_pod_logs "$namespace" "$pod" "$location"
      done
    fi
  fi
}

dump_kafka_cluster() {
  local namespace="$1"
  local cluster="$2"

  echo "            $cluster"

  for RES in "${RESOURCES[@]}"; do
    if [[ -n "$RES" ]]; then
      get_namespaced_yamls "$namespace" "$cluster" "$RES"
    fi
  done

  get_topic_describe "$namespace" "$cluster"
  get_logs "$namespace" "$cluster"
}

dump_namespace() {
  local namespace="$1"

  local found_entities=0
  echo "    $namespace"

  if get_custom_resources "$namespace"; then
    found_entities=$((found_entities + 1))
  fi

  if get_cluster_operator_info "$namespace"; then
    found_entities=$((found_entities + 1))
  fi

  # TODO enable when drain cleaner is supported
  #get_drain_cleaner_info "$namespace"

  echo "        kafkas"
  mapfile -t CLUSTERS < <($KUBE_CLIENT get kafkas -n "$namespace" --no-headers -o=custom-columns=NAME:.metadata.name)
  if [[ ${#CLUSTERS[@]} -ne 0 ]]; then
    for cluster in "${CLUSTERS[@]}"; do
      dump_kafka_cluster "$namespace" "$cluster"
      found_entities=$((found_entities + 1))
    done
  fi

  if [[ found_entities -gt 0 ]]; then
    get_events "$namespace"
  fi

  # TODO When connect, bridge and mm2 becomes supported, iterate over their CRs in the namespace, and dump accordingly
  #if [[ -n $BRIDGE ]]; then
  #  mapfile -t BRIDGE_PODS < <($KUBE_CLIENT -n "$NAMESPACE" get po -l strimzi.io/kind=KafkaBridge,strimzi.io/name="$BRIDGE-bridge" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name)
  #  if [[ ${#BRIDGE_PODS[@]} -ne 0 ]]; then
  #    for pod in "${BRIDGE_PODS[@]}"; do
  #      echo "    $pod"
  #      get_pod_logs "$pod"
  #    done
  #  fi
  #fi
  #if [[ -n $CONNECT ]]; then
  #  mapfile -t CONNECT_PODS < <($KUBE_CLIENT -n "$NAMESPACE" get po -l strimzi.io/kind=KafkaConnect,strimzi.io/name="$CONNECT-connect" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name)
  #  if [[ ${#CONNECT_PODS[@]} -ne 0 ]]; then
  #    for pod in "${CONNECT_PODS[@]}"; do
  #      echo "    $pod"
  #      get_pod_logs "$pod"
  #    done
  #  fi
  #fi
  #if [[ -n $MM2 ]]; then
  #  mapfile -t MM2_PODS < <($KUBE_CLIENT -n "$NAMESPACE" get po -l strimzi.io/kind=KafkaMirrorMaker2,strimzi.io/name="$MM2-mirrormaker2" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name)
  #  if [[ ${#MM2_PODS[@]} -ne 0 ]]; then
  #    for pod in "${MM2_PODS[@]}"; do
  #      echo "    $pod"
  #      get_pod_logs "$pod"
  #    done
  #  fi
  #fi
}

strip_and_join() {
  local sep="$1";
  shift;
  local items="$*"
  local result=""
  for item in $items; do
    if [[ -n "$result" ]]; then
      result="${result}${sep}"
    fi
    result="${result}${item//.*/}"
  done
  echo "$result"
}

crd_list=$(strip_and_join "," "${CRDS[@]}")
# Collect namespaces where CRs can be found
mapfile -t NAMESPACES < <($KUBE_CLIENT get "$crd_list" -A --no-headers -o=custom-columns=NS:.metadata.namespace)
# Collect strimzi installations
mapfile -t OPERATOR_NAMESPACES < <($KUBE_CLIENT get deploy --field-selector "metadata.name=strimzi-cluster-operator" -A --no-headers -o=custom-columns=NS:.metadata.namespace --ignore-not-found)
# Merge and remove duplicates
NAMESPACES+=("${OPERATOR_NAMESPACES[@]}")
IFS=" " read -r -a NAMESPACES <<< "$(echo "${NAMESPACES[@]}" | tr ' ' '\n' | sort -u | tr '\n' ' ')"

# Getting non-namespaced info
get_crds
for RES in "${CLUSTER_RESOURCES[@]}"; do
  get_nonnamespaced_yamls "$RES"
done

# Getting namespaced info
echo "namespaces"
if [[ ${#NAMESPACES[@]} -ne 0 ]]; then
  for namespace in "${NAMESPACES[@]}"; do
    dump_namespace "$namespace"
  done
fi

FILENAME="report-$(date +"%d-%m-%Y_%H-%M-%S")"
OLD_DIR="$(pwd)"
cd "$OUT_DIR" || exit
zip -qr "$FILENAME".zip ./reports/
cd "$OLD_DIR" || exit
if [[ $OUT_DIR == *"tmp."* ]]; then
  # keeping the old behavior when --out-dir is not specified
  mv "$OUT_DIR"/"$FILENAME".zip ./
fi
echo "Report file $FILENAME.zip created"
} # this ensures that the entire script is downloaded #
