package io.janstenpickle.controller.events.websocket

import cats.effect.kernel.Temporal
import cats.effect.{MonadCancelThrow, Resource}
import cats.syntax.semigroupk._
import io.janstenpickle.controller.events.components.EventsState
import io.janstenpickle.controller.events.{Event, Events}
import io.janstenpickle.controller.model.event.{
  ActivityUpdateEvent,
  CommandEvent,
  ConfigEvent,
  DeviceDiscoveryEvent,
  MacroEvent,
  RemoteEvent,
  SwitchEvent
}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName}
import org.http4s.HttpRoutes

object ServerWs {
  def apply[F[_]: Temporal, G[_]: MonadCancelThrow](
    events: Events[F],
    commandFilter: Event[CommandEvent] => Boolean,
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, (EventsState[F], HttpRoutes[F])] =
    send[F, G](events, k).map {
      case (state, routes) =>
        (state, receive[F, G](events, commandFilter, k) <+> routes)
    }

  def receive[F[_]: Temporal, G[_]: MonadCancelThrow](
    events: Events[F],
    commandFilter: Event[CommandEvent] => Boolean,
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): HttpRoutes[F] = {
    val config = WebsocketEventsServer.receive[F, G, ConfigEvent]("config", events.config.publisher, k)
    val remote = WebsocketEventsServer.receive[F, G, RemoteEvent]("remote", events.remote.publisher, k)
    val switch = WebsocketEventsServer.receive[F, G, SwitchEvent]("switch", events.switch.publisher, k)
    val `macro` = WebsocketEventsServer.receive[F, G, MacroEvent]("macro", events.`macro`.publisher, k)
    val activity =
      WebsocketEventsServer.receive[F, G, ActivityUpdateEvent]("activity", events.activity.publisher, k)
    val discovery =
      WebsocketEventsServer.receive[F, G, DeviceDiscoveryEvent]("discovery", events.discovery.publisher, k)

    val commands = WebsocketEventsServer
      .send[F, G, CommandEvent]("command", events.command.subscriberStream.filterEvent(commandFilter), k)

    config <+> remote <+> switch <+> `macro` <+> activity <+> discovery <+> commands
  }

  def send[F[_]: Temporal, G[_]: MonadCancelThrow](events: Events[F], k: ResourceKleisli[G, SpanName, Span[G]])(
    implicit provide: Provide[G, F, Span[G]]
  ): Resource[F, (EventsState[F], HttpRoutes[F])] = Resource.eval(EventsState[F]).map { state =>
    val config =
      WebsocketEventsServer.send[F, G, ConfigEvent]("config", events.config.subscriberStream, k, Some(state.config))
    val remote =
      WebsocketEventsServer.send[F, G, RemoteEvent]("remote", events.remote.subscriberStream, k, Some(state.remote))
    val switch =
      WebsocketEventsServer.send[F, G, SwitchEvent]("switch", events.switch.subscriberStream, k, Some(state.switch))
    val `macro` =
      WebsocketEventsServer.send[F, G, MacroEvent]("macro", events.`macro`.subscriberStream, k, Some(state.`macro`))
    val activity =
      WebsocketEventsServer.send[F, G, ActivityUpdateEvent]("activity", events.activity.subscriberStream, k, None)
    val discovery = WebsocketEventsServer
      .send[F, G, DeviceDiscoveryEvent]("discovery", events.discovery.subscriberStream, k, Some(state.discovery))

    val commands = WebsocketEventsServer.receive[F, G, CommandEvent]("command", events.command.publisher, k)

    (state, config <+> remote <+> switch <+> `macro` <+> activity <+> discovery <+> commands)
  }
}
