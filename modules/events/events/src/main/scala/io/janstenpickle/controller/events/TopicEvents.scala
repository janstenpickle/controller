package io.janstenpickle.controller.events

import java.util.UUID

import cats.effect.{Clock, Concurrent, Sync}
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.{Applicative, Parallel}
import io.janstenpickle.controller.model.event._
import io.janstenpickle.trace4cats.inject.Trace

object TopicEvents {
  def apply[F[_]: Concurrent: Clock: Parallel: Trace]: F[Events[F]] =
    Sync[F].delay(UUID.randomUUID().toString).flatMap { source =>
      (
        Applicative[F].pure(source),
        EventPubSub.topicNonBlocking[F, RemoteEvent](1000, source),
        EventPubSub.topicNonBlocking[F, SwitchEvent](1000, source),
        EventPubSub.topicNonBlocking[F, ConfigEvent](1000, source),
        EventPubSub.topicNonBlocking[F, DeviceDiscoveryEvent](1000, source),
        EventPubSub.topicNonBlocking[F, ActivityUpdateEvent](1000, source),
        EventPubSub.topicNonBlocking[F, MacroEvent](1000, source),
        EventPubSub.topicBlocking[F, CommandEvent](50, source),
      ).parMapN(Events[F])
    }
}
