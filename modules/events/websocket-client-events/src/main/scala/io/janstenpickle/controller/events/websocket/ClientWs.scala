package io.janstenpickle.controller.events.websocket

import java.net.URI

import cats.Parallel
import cats.effect.concurrent.Deferred
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Timer}
import cats.instances.list._
import cats.syntax.apply._
import cats.syntax.parallel._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber, Events}
import io.janstenpickle.controller.model.event.{
  ActivityUpdateEvent,
  CommandEvent,
  ConfigEvent,
  DeviceDiscoveryEvent,
  MacroEvent,
  RemoteEvent,
  SwitchEvent
}
import cats.syntax.functor._
import natchez.Trace

object ClientWs {
  case class SubscriberState[F[_], A](state: Option[Deferred[F, List[A]]], subscriber: EventSubscriber[F, A])
//  object SubscriberState {
//    def apply[F[_], A](state: F[List[A]], pubSub: EventSubscriber[F, A]): SubscriberState[F, A] =
//      SubscriberState(Some(state), pubSub)
//
//    def apply[F[_], A](sub: EventSubscriber[F, A]): SubscriberState[F, A] =
//      SubscriberState(None, sub)
//  }

  def receive[F[_]: Concurrent: Parallel: Timer: ContextShift, G[_]: ConcurrentEffect: Timer](
    host: NonEmptyString,
    port: PortNumber,
    blocker: Blocker,
    configPublisher: EventPublisher[F, ConfigEvent],
    remotePublisher: EventPublisher[F, RemoteEvent],
    switchPublisher: EventPublisher[F, SwitchEvent],
    macroPublisher: EventPublisher[F, MacroEvent],
    activityPublisher: EventPublisher[F, ActivityUpdateEvent],
    discoveryPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    commandSubscriber: EventSubscriber[F, CommandEvent]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] =
    List(
      JavaWebsocket.receive[F, G, ConfigEvent](host, port, "config", blocker, configPublisher),
      JavaWebsocket.receive[F, G, RemoteEvent](host, port, "remote", blocker, remotePublisher),
      JavaWebsocket.receive[F, G, SwitchEvent](host, port, "switch", blocker, switchPublisher),
      JavaWebsocket.receive[F, G, MacroEvent](host, port, "macro", blocker, macroPublisher),
      JavaWebsocket.receive[F, G, ActivityUpdateEvent](host, port, "activity", blocker, activityPublisher),
      JavaWebsocket.receive[F, G, DeviceDiscoveryEvent](host, port, "discovery", blocker, discoveryPublisher),
      JavaWebsocket.send[F, G, CommandEvent](host, port, "command", blocker, commandSubscriber)
    ).parSequence_

//  def sendForComponents[F[_]: Concurrent: Parallel: Timer, G[_]: ConcurrentEffect: ContextShift: Timer](
//    host: NonEmptyString,
//    port: PortNumber,
//    source: String,
//    blocker: Blocker,
//    components: Components[F],
//    configSubscriber: EventSubscriber[F, ConfigEvent],
//    remoteSubscriber: EventSubscriber[F, RemoteEvent],
//    switchSubscriber: EventSubscriber[F, SwitchEvent],
//    macroSubscriber: EventSubscriber[F, MacroEvent],
//    activitySubscriber: EventSubscriber[F, ActivityUpdateEvent],
//    discoverySubscriber: EventSubscriber[F, DeviceDiscoveryEvent],
//    commandPublisher: EventPublisher[F, CommandEvent]
//  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] = {
//    import io.janstenpickle.controller.events.components.ComponentsStateToEvents._
//
//    send[F, G](
//      host,
//      port,
//      blocker,
//      SubscriberState(
//        (
//          activities(components.activityConfig, source),
//          remotes(components.remoteConfig, source),
//          macroConfig(components.macroConfig, source),
//          buttons(components.buttonConfig, source)
//        ).mapN(_ ++ _ ++ _ ++ _),
//        configSubscriber
//      ),
//      SubscriberState(remoteControls(components.remotes, source), remoteSubscriber),
//      SubscriberState(switches(components.switches), switchSubscriber),
//      SubscriberState(macros(components.macroConfig), macroSubscriber),
//      SubscriberState(activitySubscriber),
//      SubscriberState(discovery(components.rename), discoverySubscriber),
//      commandPublisher
//    )
//  }

//  def send[F[_]: Concurrent: Parallel: Timer, G[_]: ConcurrentEffect: ContextShift: Timer](
//    host: NonEmptyString,
//    port: PortNumber,
//    blocker: Blocker,
//    configState: EventSubscriber[F, ConfigEvent],
//    remoteState: EventSubscriber[F, RemoteEvent],
//    switchState: EventSubscriber[F, SwitchEvent],
//    macroState: EventSubscriber[F, MacroEvent],
//    activityState: EventSubscriber[F, ActivityUpdateEvent],
//    discoveryState: EventSubscriber[F, DeviceDiscoveryEvent],
//    commandPublisher: EventPublisher[F, CommandEvent]
//  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] =
//    send[F, G](
//      host,
//      port,
//      blocker,
//      SubscriberState(configState),
//      SubscriberState(remoteState),
//      SubscriberState(switchState),
//      SubscriberState(macroState),
//      SubscriberState(activityState),
//      SubscriberState(discoveryState),
//      commandPublisher
//    )

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
