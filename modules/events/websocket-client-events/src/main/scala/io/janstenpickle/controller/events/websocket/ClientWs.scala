package io.janstenpickle.controller.events.websocket

import cats.Parallel
import cats.data.Kleisli
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.instances.list._
import cats.syntax.functor._
import cats.syntax.parallel._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
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
import io.janstenpickle.trace4cats.base.context.Provide

object ClientWs {
  def receive[F[_]: Async: Parallel, G[_]: Async, Ctx](
    host: NonEmptyString,
    port: PortNumber,
    events: Events[F],
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, Unit] =
    List(
      JavaWebsocket.receive[F, G, ConfigEvent, Ctx](host, port, "config", events.config.publisher, k),
      JavaWebsocket.receive[F, G, RemoteEvent, Ctx](host, port, "remote", events.remote.publisher, k),
      JavaWebsocket.receive[F, G, SwitchEvent, Ctx](host, port, "switch", events.switch.publisher, k),
      JavaWebsocket.receive[F, G, MacroEvent, Ctx](host, port, "macro", events.`macro`.publisher, k),
      JavaWebsocket
        .receive[F, G, ActivityUpdateEvent, Ctx](host, port, "activity", events.activity.publisher, k),
      JavaWebsocket
        .receive[F, G, DeviceDiscoveryEvent, Ctx](host, port, "discovery", events.discovery.publisher, k),
      JavaWebsocket.send[F, G, CommandEvent, Ctx](host, port, "command", events.command.subscriberStream, k),
    ).parSequence_

  def send[F[_]: Async: Parallel, G[_]: Async, Ctx](
    host: NonEmptyString,
    port: PortNumber,
    events: Events[F],
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, EventsState[F]] =
    Resource.eval(EventsState[F]).flatMap { state =>
      List(
        JavaWebsocket
          .send[F, G, ConfigEvent, Ctx](host, port, "config", events.config.subscriberStream, k, Some(state.config)),
        JavaWebsocket
          .send[F, G, RemoteEvent, Ctx](host, port, "remote", events.remote.subscriberStream, k, Some(state.remote)),
        JavaWebsocket
          .send[F, G, SwitchEvent, Ctx](host, port, "switch", events.switch.subscriberStream, k, Some(state.switch)),
        JavaWebsocket
          .send[F, G, MacroEvent, Ctx](host, port, "macro", events.`macro`.subscriberStream, k, Some(state.`macro`)),
        JavaWebsocket
          .send[F, G, ActivityUpdateEvent, Ctx](host, port, "activity", events.activity.subscriberStream, k, None),
        JavaWebsocket
          .send[F, G, DeviceDiscoveryEvent, Ctx](
            host,
            port,
            "discovery",
            events.discovery.subscriberStream,
            k,
            Some(state.discovery)
          ),
        JavaWebsocket.receive[F, G, CommandEvent, Ctx](host, port, "command", events.command.publisher, k)
      ).parSequence_.as(state)
    }
}
