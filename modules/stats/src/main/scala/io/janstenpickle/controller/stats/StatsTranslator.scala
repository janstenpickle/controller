package io.janstenpickle.controller.stats

import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.model.event._

import scala.concurrent.duration._

object StatsTranslator {
  def apply[F[_]: Concurrent](
    configPubsub: EventPubSub[F, ConfigEvent],
    activityPubsub: EventPubSub[F, ActivityUpdateEvent],
    switchPubsub: EventPubSub[F, SwitchEvent],
    remotePubsub: EventPubSub[F, RemoteEvent],
    macroPubsub: EventPubSub[F, MacroEvent],
    sink: Pipe[F, Stats, Unit]
  )(implicit timer: Timer[F]): Resource[F, Unit] = Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
    def repeatStream(stream: Stream[F, Unit]): F[Unit] =
      stream.compile.drain.handleErrorWith { th =>
        logger.error(th)("Stats stream failed, restarting") *> timer.sleep(15.seconds) *> repeatStream(stream)
      }

    for {
      configSubscriber <- configPubsub.subscriberResource
      activitySubscriber <- activityPubsub.subscriberResource
      switchSubscriber <- switchPubsub.subscriberResource
      remoteSubscriber <- remotePubsub.subscriberResource
      macroSubscriber <- macroPubsub.subscriberResource
      stream = ConfigStatsTranslator(configSubscriber)
        .merge(ActivityStatsTranslator(activitySubscriber))
        .merge(SwitchStatsTranslator(switchSubscriber))
        .merge(RemoteStatsTranslator(remoteSubscriber))
        .merge(MacroStatsTranslator(macroSubscriber))
        .through(sink)
      _ <- repeatStream(stream).background
    } yield ()
  }

}
