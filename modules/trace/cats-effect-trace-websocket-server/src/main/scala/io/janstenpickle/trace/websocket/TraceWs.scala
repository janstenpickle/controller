package io.janstenpickle.trace.websocket

import java.util.UUID

import cats.effect.Sync
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.Pipe
import io.janstenpickle.trace.avro.AvroInstances
import io.janstenpickle.trace.model.CompletedSpan
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.io.DecoderFactory
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Binary

class TraceWs[F[_]: Sync] private (schema: Schema, sink: Pipe[F, CompletedSpan, Unit]) extends Http4sDsl[F] {

  val pipe: Pipe[F, WebSocketFrame, CompletedSpan] = _.map {
    case Binary(data, true) => Some(data.toArray)
    case _ => None
  }.unNone.evalMap { bytes =>
    Sync[F]
      .delay {
        val reader = new GenericDatumReader[Any](schema)
        val decoder = DecoderFactory.get.binaryDecoder(bytes, null)
        val record = reader.read(null, decoder)

        record
      }
      .flatMap { record =>
        Sync[F].fromEither(AvroInstances.completedSpanCodec.decode(record, schema).leftMap(_.throwable))
      }
  }

  val routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root / "trace" =>
      WebSocketBuilder[F].build(fs2.Stream.empty, pipe.andThen(sink))
  }
}

object TraceWs {
  def apply[F[_]: Sync](sink: Pipe[F, CompletedSpan, Unit]): F[TraceWs[F]] =
    Sync[F].fromEither(AvroInstances.completedSpanCodec.schema.leftMap(_.throwable)).map(new TraceWs[F](_, sink))
}
