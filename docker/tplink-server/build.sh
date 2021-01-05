#!/usr/bin/env bash

cp ../../modules/plugins/tplink-server/target/graalvm-native-image/controller-plugin-tplink-server .

docker build -t janstenpickle/controller-tplink-server:$GITHUB_RUN_NUMBER .
docker push janstenpickle/controller-tplink-server:$GITHUB_RUN_NUMBER