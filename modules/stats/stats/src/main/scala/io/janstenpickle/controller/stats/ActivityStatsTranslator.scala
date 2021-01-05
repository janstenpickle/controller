package io.janstenpickle.controller.stats

import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.event.ActivityUpdateEvent

object ActivityStatsTranslator {
  def apply[F[_]](activitySubscriber: EventSubscriber[F, ActivityUpdateEvent]): fs2.Stream[F, Stats] =
    activitySubscriber.subscribe.map {
      case ActivityUpdateEvent(room, name, None) => Stats.SetActivity(room, name)
      case ActivityUpdateEvent(room, name, Some(_)) => Stats.ActivityError(room, name)
    }
}
