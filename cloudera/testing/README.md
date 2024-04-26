# Running Strimzi tests in docker (in docker)

These scripts are shooting up an env with minikube and starts unit or system tests.

## unit test
```
./run_tests_in_docker.sh unit
```

## system test
Before running this locally, the project should be compiled via `make java_install`.
```
./run_tests_in_docker.sh system <docker registry> <docker org> <docker tag> <test profile> <mvn args>
```
* docker registry: `docker-private.infra.cloudera.com`
* docker org: `cloudera`
* docker tag: strimzi image's tag, e.g. `0.40.0.1.0.0-b264`
* test profile: profile of systemtest maven module, it defines what tests will be executed, by default: `cloudera` (all tests except some exclusions)
* mvn args: additional maven args

example:
```
./run_tests_in_docker.sh system docker-private.infra.cloudera.com cloudera 0.40.0.1.0.0-b264 cloudera -Dit.test=SecurityST
```
