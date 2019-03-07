package io.janstenpickle.controller.api

import fs2.concurrent.Topic

case class UpdateTopics[F[_]](
  activities: Topic[F, Boolean],
  buttons: Topic[F, Boolean],
  remotes: Topic[F, Boolean],
  rooms: Topic[F, Boolean]
)
