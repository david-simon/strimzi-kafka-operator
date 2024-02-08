#!/usr/bin/env bash
# Self contained Strimzi thread dump tool.
set -Eeuo pipefail
if [[ $(uname -s) == "Darwin" ]]; then
  shopt -s expand_aliases
  alias echo="gecho"; alias grep="ggrep"; alias sed="gsed"; alias date="gdate"
fi

error() {
  echo "$@" 1>&2 && exit 1
}

{ # this ensures that the entire script is downloaded #
KUBECTL_INSTALLED=false
OC_INSTALLED=false
KUBE_CLIENT="kubectl"
CONTAINER=""
OUT_DIR=""
DUMPS=1
INTERVAL=5
readonly JCMD_LIST_CMD="jcmd -l | grep -v JCmd"
readonly JCMD_DUMP_CMD_TMPL="jcmd PID Thread.print"

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
Usage: java_thread_dump.sh [options]
This tool dumps the threads of all Java processes running in the containers of a specific pod.

Required:
  --namespace=<string>          Kubernetes namespace.
  --pod=<string>                Pod name. Must be a cluster operator, entity operator, kafka, zookeeper or cruise control pod.

Optional:
  --container=<string>          Container name to limit the thread dump to. By default, all containers are captured with thread dump.
  --out-dir=<string>            Script output directory.
  --dumps=<int>                 Number of thread dumps to capture. 1 by default.
  --interval=<int>              Number of seconds to wait between 2 dumps. 5 by default.
"
OPTSPEC=":-:"
while getopts "$OPTSPEC" optchar; do
  case "${optchar}" in
    -)
      case "${OPTARG}" in
        namespace=*)
          NAMESPACE=${OPTARG#*=} && readonly NAMESPACE
          ;;
        pod=*)
          POD=${OPTARG#*=} && readonly POD
          ;;
        container=*)
          CONTAINER=${OPTARG#*=} && readonly CONTAINER
          ;;
        out-dir=*)
          OUT_DIR=${OPTARG#*=}
          OUT_DIR=${OUT_DIR//\~/$HOME} && readonly OUT_DIR
          ;;
        dumps=*)
          DUMPS=${OPTARG#*=} && readonly DUMPS
          ;;
        interval=*)
          INTERVAL=${OPTARG#*=} && readonly INTERVAL
          ;;
        *)
          error "$USAGE"
          ;;
      esac;;
  esac
done
shift $((OPTIND-1))

if [[ -z $NAMESPACE || -z $POD ]]; then
  error "$USAGE"
fi

if [[ -z $OUT_DIR ]]; then
  OUT_DIR="$(mktemp -d)"
fi

if [[ -z $($KUBE_CLIENT get ns "$NAMESPACE" -o name --ignore-not-found) ]]; then
  error "Namespace $NAMESPACE not found! Exiting"
fi

mkdir -p "$OUT_DIR/dumps"

declare -a containers
if [[ -z $CONTAINER ]]; then
  container_list=$($KUBE_CLIENT get pod -n "$NAMESPACE" "$POD" -ojsonpath="{.spec.containers[*].name}")
  for c in $container_list;
  do
    containers+=("$c")
  done
else
  containers+=("$CONTAINER")
fi

dump_count=0
for (( i=0 ; i<DUMPS ; i++ ));
do
    if [[ $i -ne 0 ]]; then
      echo "Backing off for ${INTERVAL}s"
      sleep "$INTERVAL"
    fi

    for c in "${containers[@]}";
    do
      java_processes_list=$($KUBE_CLIENT exec -n "$NAMESPACE" "$POD" -c "$c" -- /bin/bash -c "$JCMD_LIST_CMD" 2>/dev/null) || true
      if [[ -z "$java_processes_list" ]]; then
        echo "Skipping container $c as it does not have a running Java process"
        continue
      fi

      declare -a jprocesses
      jprocesses=()
      while read -r line
      do
          jprocesses+=("$line")
      done <<< "$java_processes_list"

      mkdir -p "$OUT_DIR/dumps/$c"

      for line in "${jprocesses[@]}"; do
          pid=$(echo "$line" | cut -f1 -d' ')
          main_class=$(echo "$line" | cut -f2 -d' ')

          echo "Dumping threads from container ${c} PID ${pid} main class ${main_class} #${i}"

          dump_file_name="thread_dump-${c}-${pid}-${main_class}"
          if [[ $DUMPS -ne 1 ]]; then
            dump_file_name+="-$i"
          fi
          dump_file_name+=".txt"

          dump_cmd=${JCMD_DUMP_CMD_TMPL/"PID"/"$pid"}
          $KUBE_CLIENT exec -n "$NAMESPACE" "$POD" -c "$c" -- /bin/bash -c "$dump_cmd" > "${OUT_DIR}/dumps/${c}/$dump_file_name"
          ((++dump_count))
      done
    done
done

if [[ $dump_count -eq 0 ]]; then
  error "Could not capture any thread dumps in the specified pod"
fi

FILENAME="tdumps-${NAMESPACE}-${POD}-$(date +"%d-%m-%Y_%H-%M-%S")"
OLD_DIR="$(pwd)"
cd "$OUT_DIR" || exit
zip -qr "$FILENAME".zip ./dumps/
cd "$OLD_DIR" || exit
if [[ $OUT_DIR == *"tmp."* ]]; then
  # keeping the old behavior when --out-dir is not specified
  mv "$OUT_DIR"/"$FILENAME".zip ./
fi
echo "Thread dump collection file $FILENAME.zip created"
} # this ensures that the entire script is downloaded #