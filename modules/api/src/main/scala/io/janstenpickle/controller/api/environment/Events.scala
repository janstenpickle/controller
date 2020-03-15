package io.janstenpickle.controller.api.environment

import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.model.event._

case class Events[F[_]](
  remote: EventPubSub[F, RemoteEvent],
  switch: EventPubSub[F, SwitchEvent],
  config: EventPubSub[F, ConfigEvent],
  discovery: EventPubSub[F, DeviceDiscoveryEvent],
  activity: EventPubSub[F, ActivityUpdateEvent],
  `macro`: EventPubSub[F, MacroEvent],
  command: EventPubSub[F, CommandEvent]
)
