package io.janstenpickle.controller.events

import cats.effect.MonadCancelThrow
import cats.~>
import io.janstenpickle.controller.model.event._

case class Events[F[_]](
  source: String,
  remote: EventPubSub[F, RemoteEvent],
  switch: EventPubSub[F, SwitchEvent],
  config: EventPubSub[F, ConfigEvent],
  discovery: EventPubSub[F, DeviceDiscoveryEvent],
  activity: EventPubSub[F, ActivityUpdateEvent],
  `macro`: EventPubSub[F, MacroEvent],
  command: EventPubSub[F, CommandEvent]
) {
  def mapK[G[_]: MonadCancelThrow](fk: F ~> G, gk: G ~> F)(implicit F: MonadCancelThrow[F]): Events[G] =
    Events(
      source,
      remote.mapK(fk, gk),
      switch.mapK(fk, gk),
      config.mapK(fk, gk),
      discovery.mapK(fk, gk),
      activity.mapK(fk, gk),
      `macro`.mapK(fk, gk),
      command.mapK(fk, gk)
    )
}
