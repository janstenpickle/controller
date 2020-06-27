#!/usr/bin/env bash

cp ../../modules/plugins/broadlink-server/target/graalvm-native-image/controller-plugin-broadlink-server .

docker build -t janstenpickle/controller-broadlink-server:$GITHUB_RUN_NUMBER .
docker push janstenpickle/controller-broadlink-server:$GITHUB_RUN_NUMBER