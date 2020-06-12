package io.janstenpickle.controller.events

import cats.effect.Bracket
import cats.{~>, Applicative, Defer}
import io.janstenpickle.controller.model.event._

case class Events[F[_]](
  remote: EventPubSub[F, RemoteEvent],
  switch: EventPubSub[F, SwitchEvent],
  config: EventPubSub[F, ConfigEvent],
  discovery: EventPubSub[F, DeviceDiscoveryEvent],
  activity: EventPubSub[F, ActivityUpdateEvent],
  `macro`: EventPubSub[F, MacroEvent],
  command: EventPubSub[F, CommandEvent]
) {
  def mapK[G[_]: Defer: Applicative](fk: F ~> G, gk: G ~> F)(implicit F: Bracket[F, Throwable]): Events[G] =
    Events(
      remote.mapK(fk, gk),
      switch.mapK(fk, gk),
      config.mapK(fk, gk),
      discovery.mapK(fk, gk),
      activity.mapK(fk, gk),
      `macro`.mapK(fk, gk),
      command.mapK(fk, gk)
    )
}
