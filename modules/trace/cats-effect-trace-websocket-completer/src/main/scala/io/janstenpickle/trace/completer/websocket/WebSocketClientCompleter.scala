package io.janstenpickle.trace.completer.websocket

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.syntax.either._
import fs2.concurrent.Queue
import io.janstenpickle.controller.websocket.client.JavaWebSocketClient
import io.janstenpickle.trace.SpanCompleter
import io.janstenpickle.trace.avro.AvroInstances
import io.janstenpickle.trace.model._
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.EncoderFactory

object WebSocketClientCompleter {

  def apply[F[_]: ConcurrentEffect: Timer: ContextShift](
    host: String,
    port: Int,
    bufferSize: Int,
    blocker: Blocker
  ): Resource[F, SpanCompleter[F]] = {
    val uri = new URI(s"ws://$host:$port/trace")

    for {
      schema <- Resource.liftF(Sync[F].fromEither(AvroInstances.completedSpanCodec.schema.leftMap(_.throwable)))
      queue <- Resource.liftF(Queue.circularBuffer[F, CompletedSpan](bufferSize))
      stream = queue.dequeue
        .evalMap(span => Sync[F].fromEither(AvroInstances.completedSpanCodec.encode(span).leftMap(_.throwable)))
        .evalMap { record =>
          Sync[F].delay {
            val writer = new GenericDatumWriter[Any](schema)
            val out = new ByteArrayOutputStream

            val encoder = EncoderFactory.get.binaryEncoder(out, null)

            writer.write(record, encoder)
            encoder.flush()
            ByteBuffer.wrap(out.toByteArray)
          }
        }
      _ <- JavaWebSocketClient.sendBytes[F, F](uri, blocker, stream)
    } yield
      new SpanCompleter[F] {
        override def complete(span: CompletedSpan): F[Unit] = queue.enqueue1(span)
      }
  }
}
