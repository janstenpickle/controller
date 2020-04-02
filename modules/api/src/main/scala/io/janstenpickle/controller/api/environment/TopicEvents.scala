package io.janstenpickle.controller.api.environment

import cats.Parallel
import cats.effect.{Clock, Concurrent}
import cats.syntax.parallel._
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.model.event._

object TopicEvents {
  def apply[F[_]: Concurrent: Clock: Parallel]: F[Events[F]] =
    (
      EventPubSub.topicNonBlocking[F, RemoteEvent](1000),
      EventPubSub.topicNonBlocking[F, SwitchEvent](1000),
      EventPubSub.topicNonBlocking[F, ConfigEvent](1000),
      EventPubSub.topicNonBlocking[F, DeviceDiscoveryEvent](1000),
      EventPubSub.topicNonBlocking[F, ActivityUpdateEvent](1000),
      EventPubSub.topicNonBlocking[F, MacroEvent](1000),
      EventPubSub.topicBlocking[F, CommandEvent](50)
    ).parMapN(Events[F])
}
