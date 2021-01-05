#!/usr/bin/env bash

cp ../../modules/plugins/kodi-server/target/graalvm-native-image/controller-plugin-kodi-server .

docker build -t janstenpickle/controller-kodi-server:$GITHUB_RUN_NUMBER .
docker push janstenpickle/controller-kodi-server:$GITHUB_RUN_NUMBER