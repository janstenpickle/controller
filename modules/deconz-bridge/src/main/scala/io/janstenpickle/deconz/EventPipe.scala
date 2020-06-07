package io.janstenpickle.deconz

import fs2.Pipe
import io.circe.Decoder
import io.circe.generic.auto._
import io.circe.parser.parse
import io.janstenpickle.deconz.model.ButtonAction.fromInt
import io.janstenpickle.deconz.model.{ButtonAction, Event}

object EventPipe {
  implicit val buttonEventDecoder: Decoder[ButtonAction] = Decoder.decodeInt.emap(fromInt)

  def pipe[F[_]]: Pipe[F, String, Event] = _.map(parse(_).flatMap(_.as[Event]).toOption).unNone
}
