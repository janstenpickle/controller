#!/usr/bin/env bash

cp ../../modules/all-in-one/target/graalvm-native-image/controller-all-in-one .

docker build -t janstenpickle/controller-all-in-one:$GITHUB_RUN_NUMBER .
docker push janstenpickle/controller-all-in-one:$GITHUB_RUN_NUMBER