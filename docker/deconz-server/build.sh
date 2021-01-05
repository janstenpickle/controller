#!/usr/bin/env bash

cp ../../modules/deconz-server/target/graalvm-native-image/controller-deconz-server .

docker build -t janstenpickle/controller-deconz-server:$GITHUB_RUN_NUMBER .
docker push janstenpickle/controller-deconz-server:$GITHUB_RUN_NUMBER