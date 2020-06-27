package io.janstenpickle.trace.completer.jaeger

import java.nio.ByteBuffer

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import fs2.concurrent.Queue
import io.jaegertracing.thrift.internal.senders.UdpSender
import io.jaegertracing.thriftjava.{Process, Span}
import io.janstenpickle.trace.SpanCompleter
import io.janstenpickle.trace.model.CompletedSpan
import cats.effect.syntax.concurrent._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import fs2.Stream

import scala.util.Try

object JaegerSpanCompleter {
  def apply[F[_]: Concurrent: ContextShift: Timer](
    serviceName: String,
    blocker: Blocker,
    host: String = Option(System.getenv("JAEGER_AGENT_HOST")).getOrElse(UdpSender.DEFAULT_AGENT_UDP_HOST),
    port: Int = Option(System.getenv("JAEGER_AGENT_PORT"))
      .flatMap(p => Try(p.toInt).toOption)
      .getOrElse(UdpSender.DEFAULT_AGENT_UDP_COMPACT_PORT),
    bufferSize: Int = 2000,
    batchSize: Int = 50,
    batchTimeout: FiniteDuration = 10.seconds
  ): Resource[F, SpanCompleter[F]] = {
    val process = new Process(serviceName)

    def convert(span: CompletedSpan): Span = {
      val traceIdBuffer = ByteBuffer.wrap(span.context.traceId.value)
      val traceIdLow = traceIdBuffer.getLong
      val traceIdHigh = traceIdBuffer.getLong

      new Span(
        traceIdLow,
        traceIdHigh,
        ByteBuffer.wrap(span.context.spanId.value).getLong,
        span.context.parent.map(id => ByteBuffer.wrap(id.value).getLong).getOrElse(0),
        span.name,
        if (span.context.traceFlags.sampled) 1 else 0,
        span.start,
        span.end - span.start
      )
    }
    for {
      sender <- Resource.make(Sync[F].delay(new UdpSender(host, port, 0)))(sender => Sync[F].delay(sender.close()))
      queue <- Resource.liftF(Queue.circularBuffer[F, CompletedSpan](bufferSize))
      _ <- Stream
        .retry(
          queue.dequeue
            .groupWithin(batchSize, batchTimeout)
            .evalMap { spans =>
              blocker.delay(sender.send(process, spans.map(convert).toList.asJava))
            }
            .compile
            .drain,
          5.seconds,
          _ + 1.second,
          Int.MaxValue
        )
        .compile
        .drain
        .background
    } yield
      new SpanCompleter[F] {
        override def complete(span: CompletedSpan): F[Unit] = queue.enqueue1(span)
      }
  }
}
