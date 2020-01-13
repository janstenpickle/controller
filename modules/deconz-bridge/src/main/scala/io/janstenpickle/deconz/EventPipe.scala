package io.janstenpickle.deconz

import fs2.Pipe
import io.circe.Json
import io.circe.parser.parse
import io.circe.generic.auto._
import io.janstenpickle.deconz.model.Event

object EventPipe {
  def pipe[F[_]]: Pipe[F, String, Event] = _.map(parse(_).flatMap(_.as[Event]).toOption).unNone
}
