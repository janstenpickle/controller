package io.janstenpickle.trace.websocket

import java.nio.ByteBuffer

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import io.jaegertracing.thrift.internal.senders.UdpSender
import io.jaegertracing.thriftjava.{Process, Span}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.all._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object TraceListener extends IOApp {
  val udpSender = new UdpSender()

  override def run(args: List[String]): IO[ExitCode] =
    fs2.Stream
      .eval(
        TraceWs[IO](
          serviceName =>
            _.groupWithin(100, 1.seconds).evalMap { spans =>
              val process = new Process(serviceName)

              val jaegerSpans = spans.map {
                span =>
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

              IO.delay(udpSender.send(process, jaegerSpans.toList.asJava))

          }
        )
      )
      .flatMap { ws =>
        fs2.Stream.resource(Blocker[IO]).flatMap { blocker =>
          BlazeServerBuilder[IO](blocker.blockingContext)
            .bindHttp(9999, "0.0.0.0")
            .withHttpApp(ws.routes.orNotFound)
            .serve
        }
      }
      .compile
      .toList
      .map(_.head)
}
