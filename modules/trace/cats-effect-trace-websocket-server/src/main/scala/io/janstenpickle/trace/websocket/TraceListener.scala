package io.janstenpickle.trace.websocket

import java.nio.ByteBuffer

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import io.jaegertracing.thrift.internal.senders.UdpSender
import io.jaegertracing.thriftjava.{Process, Span, Tag, TagType}
import io.janstenpickle.trace.model.TraceValue.{BooleanValue, NumberValue, StringValue}
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

                  val thriftSpan = new Span(
                    traceIdLow,
                    traceIdHigh,
                    ByteBuffer.wrap(span.context.spanId.value).getLong,
                    span.context.parent.map(id => ByteBuffer.wrap(id.value).getLong).getOrElse(0),
                    span.name,
                    if (span.context.traceFlags.sampled) 1 else 0,
                    span.start,
                    span.end - span.start
                  )

                  val tags = span.attributes.view.map {
                    case (key, StringValue(value)) =>
                      val tag = new Tag(key, TagType.STRING)
                      tag.setVStr(value)
                    case (key, NumberValue(value)) =>
                      val tag = new Tag(key, TagType.DOUBLE)
                      tag.setVDouble(value)
                    case (key, BooleanValue(value)) =>
                      val tag = new Tag(key, TagType.BOOL)
                      tag.setVBool(value)
                  }.toList

                  thriftSpan.setTags(tags.asJava)
                  thriftSpan
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
