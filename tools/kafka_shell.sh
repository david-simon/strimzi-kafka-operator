#!/usr/bin/env bash
# Self contained Strimzi deployed Kafka admin shell tool.
set -Eeuo pipefail
if [[ $(uname -s) == "Darwin" ]]; then
  shopt -s expand_aliases
  alias echo="gecho"; alias grep="ggrep"; alias sed="gsed"; alias date="gdate"
fi

{ # this ensures that the entire script is downloaded #
NAMESPACE=""
CLUSTER=""
CPU_LIMIT="0.1"
MEM_LIMIT="100M"
KUBECTL_INSTALLED=false
OC_INSTALLED=false
KUBE_CLIENT="kubectl"

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

readonly USAGE="\
Strimzi deployed Kafka admin shell tool.

It starts a bash shell in a pod which is prepared with kafka-client configs.
It allows both interactive and command-pipe executions.

Usage:
1) interactive shell:
$0 [options]

2) with pipe commands:
$0 [options] << EOF
 ls -la
 echo Second command
EOF

Required:
  --namespace=<string>          Kubernetes namespace.
  --cluster=<string>            Kafka cluster name.

Optional:
  --cpu=<string>                CPU resource limit of the pod (0.1 by default)
  --mem=<string>                mem resource limit of the pod (100M by default)
"
OPTSPEC=":-:"
while getopts "$OPTSPEC" optchar; do
  case "${optchar}" in
    -)
      case "${OPTARG}" in
        namespace=*)
          NAMESPACE=${OPTARG#*=} && readonly NAMESPACE
          ;;
        cluster=*)
          CLUSTER=${OPTARG#*=} && readonly CLUSTER
          ;;
        cpu=*)
          CPU_LIMIT=${OPTARG#*=} && readonly CPU_LIMIT
          ;;
        mem=*)
          MEM_LIMIT=${OPTARG#*=} && readonly MEM_LIMIT
          ;;
        *)
          error "$USAGE"
          ;;
      esac;;
  esac
done
shift $((OPTIND-1))

if [[ -z $NAMESPACE || -z $CLUSTER ]]; then
  error "$USAGE"
fi

if [[ -z $($KUBE_CLIENT get ns "$NAMESPACE" -o name --ignore-not-found) ]]; then
  error "Namespace $NAMESPACE not found! Exiting"
fi

if [[ -z $($KUBE_CLIENT get kafkas.kafka.strimzi.io "$CLUSTER" -o name -n "$NAMESPACE" --ignore-not-found) ]]; then
  error "Kafka cluster $CLUSTER in namespace $NAMESPACE not found! Exiting"
fi

tty_option=""
[[ -t 0 ]] && tty_option="-t"

kafka_broker_labels=strimzi.io/kind=Kafka,strimzi.io/component-type=kafka,strimzi.io/name="$CLUSTER-kafka"

set +o pipefail
kafka_image="$($KUBE_CLIENT -n "$NAMESPACE" get po -l "$kafka_broker_labels" --ignore-not-found --no-headers -o=custom-columns=IMAGE:.spec.containers[0].image | head -1)"
broker_pod="$($KUBE_CLIENT -n "$NAMESPACE" get po -l "$kafka_broker_labels" --ignore-not-found --no-headers -o=custom-columns=NAME:.metadata.name | head -1)"
set -o pipefail

image_pull_secrets="$($KUBE_CLIENT -n "$NAMESPACE" get po "$broker_pod" -o=jsonpath="  imagePullSecrets:{'\n'}{range .spec.imagePullSecrets[*]}[    -name: {.name}{'\n'}]{end}")"
if [[ $image_pull_secrets != *"name"* ]]; then
  image_pull_secrets="  imagePullSecrets: []"
fi
pod_name="kafka-shell-$RANDOM"

listener_port=$($KUBE_CLIENT -n "$NAMESPACE" get configmaps "$broker_pod" -o jsonpath='{.data.server\.config}' | grep "control.plane.listener.name" | cut -d'-' -f2)
bootstrap_servers="$CLUSTER-kafka-brokers.$NAMESPACE.svc:$listener_port"


# cleanup for the pod on exit
trap '$KUBE_CLIENT -n "$NAMESPACE" delete pod "$pod_name"' EXIT

# creating a pod with the secrets, which doesn't stop
$KUBE_CLIENT -n "$NAMESPACE" create -f - << EOF || true
apiVersion: v1
kind: Pod
metadata:
  name: ${pod_name}
  labels:
    strimzi.io/cluster: ${CLUSTER}
    strimzi.io/kind: Kafka
    strimzi.io/name: ${CLUSTER}-kafka
spec:
${image_pull_secrets}
  containers:
    - resources:
        limits:
          memory: "$MEM_LIMIT"
          cpu: "$CPU_LIMIT"
      command: ["/bin/bash", "-c"]
      args:
        - |
          CERTS_STORE_PASSWORD=\$(< /dev/urandom tr -dc _A-Z-a-z-0-9 | head -c32)
          export CERTS_STORE_PASSWORD
          mkdir -p /tmp/kafka
          HOSTNAME=$CLUSTER-kafka-0 ./kafka_tls_prepare_certificates.sh
          envsubst '\${CERTS_STORE_PASSWORD}' < "/opt/kafka/custom-config/server.config" | grep -i "listener.name.\$listener." | cut -d'.' -f4- > /tmp/client.properties
          echo "security.protocol=ssl" >> /tmp/client.properties
          while true; do sleep 10; done;
      name: $pod_name
      image: $kafka_image
      env:
        - name: BOOTSTRAP_SERVERS
          value: "${bootstrap_servers}"
      volumeMounts:
        - mountPath: /tmp
          name: strimzi-tmp
        - mountPath: /opt/kafka/cluster-ca-certs
          name: cluster-ca
        - mountPath: /opt/kafka/broker-certs
          name: broker-certs
        - mountPath: /opt/kafka/client-ca-certs
          name: client-ca-cert
        - mountPath: /opt/kafka/custom-config/
          name: kafka-metrics-and-logging
  restartPolicy: Never
  terminationGracePeriodSeconds: 1
  volumes:
    - emptyDir:
        medium: Memory
        sizeLimit: 5Mi
      name: strimzi-tmp
    - name: cluster-ca
      secret:
        defaultMode: 292
        secretName: $CLUSTER-cluster-ca-cert
    - name: broker-certs
      secret:
        defaultMode: 292
        secretName: $CLUSTER-kafka-brokers
    - name: client-ca-cert
      secret:
        defaultMode: 292
        secretName: $CLUSTER-clients-ca-cert
    - configMap:
        defaultMode: 420
        name: $CLUSTER-kafka-0
      name: kafka-metrics-and-logging
EOF
$KUBE_CLIENT -n "$NAMESPACE" wait --for=condition=Ready pod "$pod_name" >> /dev/null || true

echo "Please find the client config file at /tmp/client.properties for Kafka admin tools."
echo "You can use --bootstrap.server=\$BOOTSTRAP_SERVERS"
echo "Example:"
echo "bin/kafka-topics.sh --list --command-config /tmp/client.properties --bootstrap-server \$BOOTSTRAP_SERVERS"

$KUBE_CLIENT -n "$NAMESPACE" exec "$pod_name" -i $tty_option -- /bin/bash || true
} # this ensures that the entire script is downloaded #
