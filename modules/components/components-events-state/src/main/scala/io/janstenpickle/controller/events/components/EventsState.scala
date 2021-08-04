package io.janstenpickle.controller.events.components

import cats.Monad
import cats.effect.kernel.Deferred
import cats.effect.{Clock, Concurrent}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.events.Event
import io.janstenpickle.controller.events.components.ComponentsStateToEvents._
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, MacroEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.trace4cats.inject.Trace

case class EventsState[F[_]](
  config: Deferred[F, F[List[Event[ConfigEvent]]]],
  remote: Deferred[F, F[List[Event[RemoteEvent]]]],
  switch: Deferred[F, F[List[Event[SwitchEvent]]]],
  `macro`: Deferred[F, F[List[Event[MacroEvent]]]],
  discovery: Deferred[F, F[List[Event[DeviceDiscoveryEvent]]]]
) {
  def completeWithComponents(
    components: Components[F],
    source: String,
    eventSource: String
  )(implicit F: Monad[F], clock: Clock[F], trace: Trace[F]): F[Unit] = {
    def addTrace[A](as: List[A]): F[List[Event[A]]] =
      for {
        instant <- clock.realTimeInstant
        headers <- trace.headers
      } yield as.map(Event[A](_, instant, eventSource, headers.values))

    config.complete(
      (
        activities(components.activityConfig, source),
        remotes(components.remoteConfig, source),
        macroConfig(components.macroConfig, source),
        buttons(components.buttonConfig, source)
      ).mapN(_ ++ _ ++ _ ++ _).flatMap(addTrace)
    ) *> remote.complete(remoteControls(components.remotes, source).flatMap(addTrace)) *> switch.complete(
      switches(components.switches).flatMap(addTrace)
    ) *> macros(components.macroConfig).flatMap(addTrace) *> discovery.complete(
      ComponentsStateToEvents.discovery(components.rename).flatMap(addTrace)
    )
  }.void
}

object EventsState {
  def apply[F[_]: Concurrent]: F[EventsState[F]] =
    for {
      config <- Deferred[F, F[List[Event[ConfigEvent]]]]
      remote <- Deferred[F, F[List[Event[RemoteEvent]]]]
      switch <- Deferred[F, F[List[Event[SwitchEvent]]]]
      mac <- Deferred[F, F[List[Event[MacroEvent]]]]
      discovery <- Deferred[F, F[List[Event[DeviceDiscoveryEvent]]]]
    } yield EventsState(config, remote, switch, mac, discovery)
}
