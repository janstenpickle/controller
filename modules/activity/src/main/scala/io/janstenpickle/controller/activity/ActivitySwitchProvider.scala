package io.janstenpickle.controller.activity

import cats.effect.Clock
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{MonadError, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.{Room, State, SwitchKey, SwitchMetadata, Activity => ActivityModel}
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import natchez.Trace

object ActivitySwitchProvider {
  private final val switchDevice = NonEmptyString("activity")

  // TODO publish switch events

  def apply[F[_]: MonadError[*[_], Throwable]: Parallel: Clock: Trace](
    underlying: Activity[F],
    config: ConfigSource[F, String, ActivityModel],
    macros: Macro[F],
    switchEventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  ): (Activity[F], SwitchProvider[F]) = {
    def switchName(act: ActivityModel) = NonEmptyString.unsafeFrom(s"${act.room}-${act.name}")

    def notifyUpdates(room: Room, name: NonEmptyString) =
      config.getConfig.flatMap(_.values.values.toList.parTraverse_ { act =>
        val state = if (act.room == room && act.name == name) State.On else State.Off

        switchEventPublisher.publish1(SwitchStateUpdateEvent(SwitchKey(switchDevice, switchName(act)), state))
      })

    val activity = new Activity[F] {
      override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
        underlying.setActivity(room, name) >> notifyUpdates(room, name)
      override def getActivity(room: Room): F[Option[NonEmptyString]] = underlying.getActivity(room)
    }

    def switch(act: ActivityModel): Switch[F] =
      TracedSwitch(new Switch[F] {
        override lazy val name: NonEmptyString = switchName(act)
        override lazy val device: NonEmptyString = switchDevice
        override lazy val metadata: SwitchMetadata = SwitchMetadata(room = Some(act.room.value))
        override def getState: F[State] = activity.getActivity(act.room).map {
          case Some(act.name) => State.On
          case _ => State.Off
        }
        override def switchOn: F[Unit] = activity.setActivity(act.room, act.name)
        override def switchOff: F[Unit] = ().pure[F]
      })

    val switchProvider = new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] = macros.listMacros.flatMap { ms =>
        lazy val mss: Set[String] = ms.map(_.value).toSet
        config.getConfig.map(_.values.collect {
          case (_, act) if mss.contains(s"${act.room}-${act.name}") =>
            SwitchKey(switchDevice, switchName(act)) -> switch(act)
        })
      }
    }

    activity -> switchProvider
  }
}
