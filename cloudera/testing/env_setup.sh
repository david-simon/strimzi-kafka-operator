#!/usr/bin/env sh

set -ex

ARCH=$(uname -m)
case $ARCH in
  armv5*) ARCH="armv5";;
  armv6*) ARCH="armv6";;
  armv7*) ARCH="arm";;
  aarch64) ARCH="arm64";;
  x86) ARCH="386";;
  x86_64) ARCH="amd64";;
  i686) ARCH="386";;
  i386) ARCH="386";;
esac

apk update && apk upgrade
apk add \
  bash \
  bash-completion \
  curl \
  findutils \
  helm \
  kubectl \
  kubectl-bash-completion \
  libc6-compat \
  make \
  maven \
  openjdk17-jdk \
  yq
apk cache clean

curl -sLO "https://cloudera-build-us-west-1.vpc.cloudera.com/s3/ARTIFACTS/DIM-QE/strimziTesting/minikube-linux-${ARCH}"
install "minikube-linux-${ARCH}" /usr/local/bin/minikube

cloudera_thirdparty="docker-private.infra.cloudera.com/cloudera_thirdparty"
cloudera_base="docker-private.infra.cloudera.com/cloudera_base"
registry_image="${cloudera_thirdparty}/registry:2.8.3"
docker run -d -p 5000:5000 "${registry_image}"

MINIKUBE_MEMORY=$(free -m | grep "Mem" | awk '{print int($2*0.95)}')
MINIKUBE_CPU=$(awk '$1~/cpu[0-9]/{usage=($2+$4)*100/($2+$4+$5); print $1": "usage"%"}' /proc/stat | wc -l)

export MINIKUBE_WANTUPDATENOTIFICATION=false
export MINIKUBE_WANTREPORTERRORPROMPT=false
export MINIKUBE_HOME=$HOME
export CHANGE_MINIKUBE_NONE_USER=true
if [ "${ARCH}" = "amd64" ]; then
  curl -sLO "https://cloudera-build-us-west-1.vpc.cloudera.com/s3/ARTIFACTS/DIM-QE/strimziTesting/minikube_cache.tar.gz"
  tar xzf minikube_cache.tar.gz
fi
minikube start --force --cpus=${MINIKUBE_CPU} --memory=${MINIKUBE_MEMORY} \
  --base-image="${cloudera_thirdparty}/k8s-minikube/kicbase:v0.0.42" \
  --extra-config=apiserver.authorization-mode=Node,RBAC \
  --insecure-registry=localhost:5000
minikube addons enable default-storageclass
minikube addons enable registry --images="Registry=${registry_image},KubeRegistryProxy=${cloudera_thirdparty}/k8s-minikube/kube-registry-proxy:0.0.5"
minikube addons enable registry-aliases --images="CoreDNSPatcher=${cloudera_thirdparty}/rhdevelopers/core-dns-patcher:latest,Alpine=${cloudera_base}/alpine:3.11.2,Pause=${cloudera_thirdparty}/google_containers/pause:3.1"
