#!/usr/bin/env bash

cp ../../modules/plugins/sonos-server/target/graalvm-native-image/controller-plugin-sonos-server .

docker build -t janstenpickle/controller-sonos-server:$GITHUB_RUN_NUMBER .
docker push janstenpickle/controller-sonos-server:$GITHUB_RUN_NUMBER