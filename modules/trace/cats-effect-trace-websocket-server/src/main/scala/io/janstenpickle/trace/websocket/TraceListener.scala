package io.janstenpickle.trace.websocket

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import com.google.protobuf.{ByteString, Duration, Timestamp}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.all._

//import scala.concurrent.duration._
import io.jaegertracing.api_v2.Model._

object TraceListener extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    fs2.Stream
      .eval(TraceWs[IO](_.groupWithin(100, 20.seconds).map { spans =>
        val x = spans.map { span =>
          val spanBuilder = Span
            .newBuilder()
            .setSpanId(ByteString.copyFrom(span.context.spanId.value))
            .setTraceId(ByteString.copyFrom(span.context.traceId.value))
            .setStartTime(Timestamp.newBuilder().setSeconds(span.start / 1000).build())
            .setDuration(Duration.newBuilder().setSeconds((span.end - span.start) / 1000))
            .setOperationName(span.name)
            .set

        }

        io.jaegertracing.api_v2.Model.Batch.newBuilder()

      }))
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
