ifeq ($(CLOUDERA_BUILD),true)
%:
	echo "CLOUDERA_BUILD is on, skipping '$@' on systemtest module"
else
PROJECT_NAME=systemtest

docker_build: java_install
docker_push:
docker_tag:
all: docker_build docker_push
clean: java_clean

include ../Makefile.maven

.PHONY: build clean release
endif
