package io.janstenpickle.controller.events.websocket

import cats.Applicative
import cats.effect.Concurrent
import cats.syntax.semigroupk._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.{
  ActivityUpdateEvent,
  CommandEvent,
  ConfigEvent,
  DeviceDiscoveryEvent,
  MacroEvent,
  RemoteEvent,
  SwitchEvent
}
import natchez.Trace
import org.http4s.HttpRoutes

object ServerWs {
  def apply[F[_]: Concurrent, G[_]: Applicative](
    configSubscriber: EventSubscriber[F, ConfigEvent],
    remoteSubscriber: EventSubscriber[F, RemoteEvent],
    switchSubscriber: EventSubscriber[F, SwitchEvent],
    macroSubscriber: EventSubscriber[F, MacroEvent],
    activitySubscriber: EventSubscriber[F, ActivityUpdateEvent],
    discoverySubscriber: EventSubscriber[F, DeviceDiscoveryEvent],
    commandPublisher: EventPublisher[F, CommandEvent]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val config = WebsocketEvents.subscribe[F, G, ConfigEvent]("config", configSubscriber)
    val remote = WebsocketEvents.subscribe[F, G, RemoteEvent]("remote", remoteSubscriber)
    val switch = WebsocketEvents.subscribe[F, G, SwitchEvent]("switch", switchSubscriber)
    val `macro` = WebsocketEvents.subscribe[F, G, MacroEvent]("macro", macroSubscriber)
    val activity = WebsocketEvents.subscribe[F, G, ActivityUpdateEvent]("activity", activitySubscriber)
    val discovery = WebsocketEvents.subscribe[F, G, DeviceDiscoveryEvent]("discovery", discoverySubscriber)

    val commands = WebsocketEvents.publish[F, G, CommandEvent]("command", commandPublisher)

    config <+> remote <+> switch <+> `macro` <+> activity <+> discovery <+> commands
  }
}
