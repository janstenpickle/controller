package io.janstenpickle.controller.events.websocket

import cats.Applicative
import cats.effect.Concurrent
import cats.syntax.apply._
import cats.syntax.semigroupk._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.components.Components
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
  case class SubscriberState[F[_], A](state: Option[F[List[A]]], subscriber: EventSubscriber[F, A])
  object SubscriberState {
    def apply[F[_], A](state: F[List[A]], subscriber: EventSubscriber[F, A]): SubscriberState[F, A] =
      SubscriberState(Some(state), subscriber)

    def apply[F[_], A](subscriber: EventSubscriber[F, A]): SubscriberState[F, A] =
      SubscriberState(None, subscriber)
  }

  def forComponents[F[_]: Concurrent, G[_]: Applicative](
    components: Components[F],
    source: String,
    configSubscriber: EventSubscriber[F, ConfigEvent],
    remoteSubscriber: EventSubscriber[F, RemoteEvent],
    switchSubscriber: EventSubscriber[F, SwitchEvent],
    macroSubscriber: EventSubscriber[F, MacroEvent],
    activitySubscriber: EventSubscriber[F, ActivityUpdateEvent],
    discoverySubscriber: EventSubscriber[F, DeviceDiscoveryEvent],
    commandPublisher: EventPublisher[F, CommandEvent]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    import io.janstenpickle.controller.events.components.ComponentsStateToEvents._

    apply[F, G](
      SubscriberState(
        (
          activities(components.activityConfig, source),
          remotes(components.remoteConfig, source),
          macroConfig(components.macroConfig, source),
          buttons(components.buttonConfig, source)
        ).mapN(_ ++ _ ++ _ ++ _),
        configSubscriber
      ),
      SubscriberState(remoteControls(components.remotes, source), remoteSubscriber),
      SubscriberState(switches(components.switches), switchSubscriber),
      SubscriberState(macros(components.macroConfig), macroSubscriber),
      SubscriberState(activitySubscriber),
      SubscriberState(discovery(components.rename), discoverySubscriber),
      commandPublisher,
    )
  }

  def apply[F[_]: Concurrent, G[_]: Applicative](
    configSubscriber: EventSubscriber[F, ConfigEvent],
    remoteSubscriber: EventSubscriber[F, RemoteEvent],
    switchSubscriber: EventSubscriber[F, SwitchEvent],
    macroSubscriber: EventSubscriber[F, MacroEvent],
    activitySubscriber: EventSubscriber[F, ActivityUpdateEvent],
    discoverySubscriber: EventSubscriber[F, DeviceDiscoveryEvent],
    commandPublisher: EventPublisher[F, CommandEvent]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] =
    apply[F, G](
      SubscriberState(configSubscriber),
      SubscriberState(remoteSubscriber),
      SubscriberState(switchSubscriber),
      SubscriberState(macroSubscriber),
      SubscriberState(activitySubscriber),
      SubscriberState(discoverySubscriber),
      commandPublisher,
    )

  def apply[F[_]: Concurrent, G[_]: Applicative](
    configSubscriber: SubscriberState[F, ConfigEvent],
    remoteSubscriber: SubscriberState[F, RemoteEvent],
    switchSubscriber: SubscriberState[F, SwitchEvent],
    macroSubscriber: SubscriberState[F, MacroEvent],
    activitySubscriber: SubscriberState[F, ActivityUpdateEvent],
    discoverySubscriber: SubscriberState[F, DeviceDiscoveryEvent],
    commandPublisher: EventPublisher[F, CommandEvent]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): HttpRoutes[F] = {
    val config =
      WebsocketEvents.subscribe[F, G, ConfigEvent]("config", configSubscriber.subscriber, configSubscriber.state)
    val remote =
      WebsocketEvents.subscribe[F, G, RemoteEvent]("remote", remoteSubscriber.subscriber, remoteSubscriber.state)
    val switch =
      WebsocketEvents.subscribe[F, G, SwitchEvent]("switch", switchSubscriber.subscriber, switchSubscriber.state)
    val `macro` =
      WebsocketEvents.subscribe[F, G, MacroEvent]("macro", macroSubscriber.subscriber, macroSubscriber.state)
    val activity = WebsocketEvents
      .subscribe[F, G, ActivityUpdateEvent]("activity", activitySubscriber.subscriber, activitySubscriber.state)
    val discovery = WebsocketEvents
      .subscribe[F, G, DeviceDiscoveryEvent]("discovery", discoverySubscriber.subscriber, discoverySubscriber.state)

    val commands = WebsocketEvents.publish[F, G, CommandEvent]("command", commandPublisher)

    config <+> remote <+> switch <+> `macro` <+> activity <+> discovery <+> commands
  }
}
