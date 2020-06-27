#!/usr/bin/env bash

cp ../../modules/coordinator/target/graalvm-native-image/controller-coordinator .

docker build -t janstenpickle/controller-coordinator:$GITHUB_RUN_NUMBER .
docker push janstenpickle/controller-coordinator:$GITHUB_RUN_NUMBER