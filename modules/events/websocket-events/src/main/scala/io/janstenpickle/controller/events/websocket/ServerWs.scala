package io.janstenpickle.controller.events.websocket

import cats.Applicative
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.semigroupk._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.{Event, EventPublisher, EventSubscriber, Events}
import io.janstenpickle.controller.events.components.EventsState
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
  def apply[F[_]: Concurrent: Timer: Trace, G[_]: Applicative](
    events: Events[F],
    commandFilter: Event[CommandEvent] => Boolean
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, (EventsState[F], HttpRoutes[F])] =
    send[F, G](events).map {
      case (state, routes) =>
        (state, receive[F, G](events, commandFilter) <+> routes)
    }

  def receive[F[_]: Concurrent: Timer: Trace, G[_]: Applicative](
    events: Events[F],
    commandFilter: Event[CommandEvent] => Boolean
  )(implicit liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val config = WebsocketEventsServer.receive[F, G, ConfigEvent]("config", events.config.publisher)
    val remote = WebsocketEventsServer.receive[F, G, RemoteEvent]("remote", events.remote.publisher)
    val switch = WebsocketEventsServer.receive[F, G, SwitchEvent]("switch", events.switch.publisher)
    val `macro` = WebsocketEventsServer.receive[F, G, MacroEvent]("macro", events.`macro`.publisher)
    val activity =
      WebsocketEventsServer.receive[F, G, ActivityUpdateEvent]("activity", events.activity.publisher)
    val discovery = WebsocketEventsServer.receive[F, G, DeviceDiscoveryEvent]("discovery", events.discovery.publisher)

    val commands = WebsocketEventsServer
      .send[F, G, CommandEvent]("command", events.command.subscriberStream.filterEvent(commandFilter))

    config <+> remote <+> switch <+> `macro` <+> activity <+> discovery <+> commands
  }

  def send[F[_]: Concurrent: Timer: Trace, G[_]: Applicative](events: Events[F])(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, (EventsState[F], HttpRoutes[F])] = Resource.liftF(EventsState[F]).map { state =>
    val config =
      WebsocketEventsServer.send[F, G, ConfigEvent]("config", events.config.subscriberStream, Some(state.config))
    val remote =
      WebsocketEventsServer.send[F, G, RemoteEvent]("remote", events.remote.subscriberStream, Some(state.remote))
    val switch =
      WebsocketEventsServer.send[F, G, SwitchEvent]("switch", events.switch.subscriberStream, Some(state.switch))
    val `macro` =
      WebsocketEventsServer.send[F, G, MacroEvent]("macro", events.`macro`.subscriberStream, Some(state.`macro`))
    val activity =
      WebsocketEventsServer.send[F, G, ActivityUpdateEvent]("activity", events.activity.subscriberStream, None)
    val discovery = WebsocketEventsServer
      .send[F, G, DeviceDiscoveryEvent]("discovery", events.discovery.subscriberStream, Some(state.discovery))

    val commands = WebsocketEventsServer.receive[F, G, CommandEvent]("command", events.command.publisher)

    (state, config <+> remote <+> switch <+> `macro` <+> activity <+> discovery <+> commands)
  }
}
