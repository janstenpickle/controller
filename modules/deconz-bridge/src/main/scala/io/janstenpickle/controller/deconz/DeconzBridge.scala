package io.janstenpickle.controller.deconz

import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import cats.Applicative
import cats.effect.syntax.concurrent._
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.Decoder
import io.circe.generic.auto._
import io.circe.parser.parse
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.deconz.action.{ActionProcessor, CommandEventProcessor}
import io.janstenpickle.controller.deconz.config.CirceButtonMappingConfigSource
import io.janstenpickle.controller.deconz.model.ButtonAction.fromInt
import io.janstenpickle.controller.deconz.model.{ButtonAction, Event}
import io.janstenpickle.controller.errors.ErrorHandler
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.CommandEvent
import io.janstenpickle.controller.websocket.client.JavaWebSocketClient
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.SpanStatus

import scala.concurrent.duration._

object DeconzBridge {
  case class Config(
    api: DeconzApiConfig,
    configDir: Path,
    polling: PollingConfig,
    writeTimeout: FiniteDuration = 30.seconds
  )
  case class DeconzApiConfig(host: NonEmptyString, port: PortNumber)

  implicit val buttonEventDecoder: Decoder[ButtonAction] = Decoder.decodeInt.emap(fromInt)

  def apply[F[_]: Timer: ContextShift, G[_]: ContextShift](
    config: Config,
    commandPublisher: EventPublisher[F, CommandEvent],
    blocker: Blocker,
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(
    implicit F: Concurrent[F],
    G: ConcurrentEffect[G],
    provide: Provide[G, F, Span[G]],
    timer: Timer[G],
    trace: Trace[F]
  ): Resource[F, Unit] =
    for {
      mappingSource <- ConfigFileSource
        .polling[F, G](config.configDir.resolve("deconz"), config.polling.pollInterval, blocker, config.writeTimeout, k)
      mapping <- CirceButtonMappingConfigSource[F, G](mappingSource, config.polling, _ => Applicative[F].unit, k)
      actionProcessor <- Resource.eval(CommandEventProcessor[F](commandPublisher, mapping))
      _ <- apply[F, G](config.api, actionProcessor, blocker, k)
    } yield ()

  def apply[F[_]: Concurrent: ContextShift: Timer, G[_]](
    config: DeconzApiConfig,
    processor: ActionProcessor[F],
    blocker: Blocker,
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit F: Sync[F], G: ConcurrentEffect[G], provide: Provide[G, F, Span[G]], trace: Trace[F]): Resource[F, Unit] =
    Resource.eval(Slf4jLogger.create[F]).flatMap { logger =>
      val uri = new URI(s"ws://${config.host}:${config.port}")

      def recieve(queue: Queue[F, String]): F[Unit] =
        queue.dequeue
          .map(parse(_).flatMap(_.as[Event]).toOption)
          .unNone
          .evalMapAccumulate[F, Map[(String, Boolean), Long], List[(String, ButtonAction)]](
            Map.empty[(String, Boolean), Long]
          )(
            (started, event) =>
              event.state.buttonevent match {
                case ButtonAction.LongPressOnStart =>
                  Timer[F].clock.realTime(TimeUnit.MILLISECONDS).map { ts =>
                    started.updated((event.id, true), ts) -> List.empty
                  }
                case ButtonAction.LongPressOnStop =>
                  Timer[F].clock.realTime(TimeUnit.MILLISECONDS).map { ts =>
                    (started - (event.id -> true)) -> started
                      .get((event.id, true))
                      .map { start =>
                        (event.id, ButtonAction.LongPressOn((ts - start).millis))
                      }
                      .toList
                  }
                case ButtonAction.LongPressOffStart =>
                  Timer[F].clock.realTime(TimeUnit.MILLISECONDS).map { ts =>
                    started.updated((event.id, false), ts) -> List.empty
                  }
                case ButtonAction.LongPressOffStop =>
                  Timer[F].clock.realTime(TimeUnit.MILLISECONDS).map { ts =>
                    (started - (event.id -> false)) -> started
                      .get((event.id, false))
                      .map { start =>
                        (event.id, ButtonAction.LongPressOff((ts - start).millis))
                      }
                      .toList
                  }
                case _ => Applicative[F].pure(started -> List(event.id -> event.state.buttonevent))
            }
          )
          .flatMap { case (_, events) => Stream.emits(events) }
          .evalMap {
            case (id, event) =>
              processor.process(id, event).handleErrorWith { th =>
                trace.setStatus(SpanStatus.Internal(th.getMessage)) >> logger
                  .warn(th)("Processor failed to do handle deconz event")
              }
          }
          .compile
          .drain

      for {
        queue <- Resource.eval(Queue.unbounded[F, String])
        _ <- JavaWebSocketClient.receiveString[F, G, Span[G]](uri, blocker, k, queue.enqueue1)
        _ <- Stream.retry(recieve(queue), 5.seconds, _ + 1.second, Int.MaxValue).compile.drain.background
      } yield ()
    }
}
