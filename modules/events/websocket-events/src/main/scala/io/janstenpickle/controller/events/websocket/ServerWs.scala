package io.janstenpickle.controller.events.websocket

import cats.Applicative
import cats.effect.{Concurrent, Timer}
import cats.syntax.semigroupk._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.Events
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
  def receive[F[_]: Concurrent: Timer: Trace, G[_]: Applicative](
    events: Events[F]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val config = WebsocketEventsServer.receive[F, G, ConfigEvent]("config", events.config.publisher)
    val remote = WebsocketEventsServer.receive[F, G, RemoteEvent]("remote", events.remote.publisher)
    val switch = WebsocketEventsServer.receive[F, G, SwitchEvent]("switch", events.switch.publisher)
    val `macro` = WebsocketEventsServer.receive[F, G, MacroEvent]("macro", events.`macro`.publisher)
    val activity =
      WebsocketEventsServer.receive[F, G, ActivityUpdateEvent]("activity", events.activity.publisher)
    val discovery = WebsocketEventsServer.receive[F, G, DeviceDiscoveryEvent]("discovery", events.discovery.publisher)

    val commands = WebsocketEventsServer.send[F, G, CommandEvent]("command", events.command.subscriberStream)

    config <+> remote <+> switch <+> `macro` <+> activity <+> discovery <+> commands
  }
}
