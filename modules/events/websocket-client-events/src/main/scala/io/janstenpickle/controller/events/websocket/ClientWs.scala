package io.janstenpickle.controller.events.websocket

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Timer}
import cats.instances.list._
import cats.syntax.functor._
import cats.syntax.parallel._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.Events
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

object ClientWs {
  def receive[F[_]: Concurrent: Parallel: Timer: ContextShift, G[_]: ConcurrentEffect: Timer](
    host: NonEmptyString,
    port: PortNumber,
    blocker: Blocker,
    events: Events[F]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] =
    List(
      JavaWebsocket.receive[F, G, ConfigEvent](host, port, "config", blocker, events.config.publisher),
      JavaWebsocket.receive[F, G, RemoteEvent](host, port, "remote", blocker, events.remote.publisher),
      JavaWebsocket.receive[F, G, SwitchEvent](host, port, "switch", blocker, events.switch.publisher),
      JavaWebsocket.receive[F, G, MacroEvent](host, port, "macro", blocker, events.`macro`.publisher),
      JavaWebsocket.receive[F, G, ActivityUpdateEvent](host, port, "activity", blocker, events.activity.publisher),
      JavaWebsocket.receive[F, G, DeviceDiscoveryEvent](host, port, "discovery", blocker, events.discovery.publisher),
      JavaWebsocket.send[F, G, CommandEvent](host, port, "command", blocker, events.command.subscriberStream)
    ).parSequence_

  def send[F[_]: Concurrent: Parallel: Timer: ContextShift, G[_]: ConcurrentEffect: Timer](
    host: NonEmptyString,
    port: PortNumber,
    blocker: Blocker,
    events: Events[F]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, EventsState[F]] =
    Resource.liftF(EventsState[F]).flatMap { state =>
      List(
        JavaWebsocket
          .send[F, G, ConfigEvent](host, port, "config", blocker, events.config.subscriberStream, Some(state.config)),
        JavaWebsocket
          .send[F, G, RemoteEvent](host, port, "remote", blocker, events.remote.subscriberStream, Some(state.remote)),
        JavaWebsocket
          .send[F, G, SwitchEvent](host, port, "switch", blocker, events.switch.subscriberStream, Some(state.switch)),
        JavaWebsocket
          .send[F, G, MacroEvent](host, port, "macro", blocker, events.`macro`.subscriberStream, Some(state.`macro`)),
        JavaWebsocket
          .send[F, G, ActivityUpdateEvent](host, port, "activity", blocker, events.activity.subscriberStream, None),
        JavaWebsocket
          .send[F, G, DeviceDiscoveryEvent](
            host,
            port,
            "discovery",
            blocker,
            events.discovery.subscriberStream,
            Some(state.discovery)
          ),
        JavaWebsocket.receive[F, G, CommandEvent](host, port, "command", blocker, events.command.publisher)
      ).parSequence_.as(state)
    }
}
