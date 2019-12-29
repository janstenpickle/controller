package io.janstenpickle.controller.activity

import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{FlatMap, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.{Room, State, Activity => ActivityModel}
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Metadata, Switch, SwitchProvider}

object ActivitySwitchProvider {
  private final val switchDevice = NonEmptyString("activity")

  def apply[F[_]: FlatMap: Parallel](
    underlying: Activity[F],
    config: ConfigSource[F, String, ActivityModel],
    macros: Macro[F],
    onSwitchUpdate: SwitchKey => F[Unit]
  ): (Activity[F], SwitchProvider[F]) = {
    def switchName(act: ActivityModel) = NonEmptyString.unsafeFrom(s"${act.room}-${act.name}")

    def notifyUpdates =
      config.getConfig.flatMap(_.values.values.toList.parTraverse_ { act =>
        onSwitchUpdate(SwitchKey(switchDevice, switchName(act)))
      })

    val activity = new Activity[F] {
      override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
        underlying.setActivity(room, name) >> notifyUpdates
      override def getActivity(room: Room): F[Option[NonEmptyString]] = underlying.getActivity(room)
    }

    def switch(act: ActivityModel): Switch[F] = new Switch[F] {
      override lazy val name: NonEmptyString = switchName(act)
      override lazy val device: NonEmptyString = switchDevice
      override lazy val metadata: Metadata = Metadata()
      override def getState: F[State] = activity.getActivity(act.room).map {
        case Some(act.name) => State.On
        case _ => State.Off
      }
      override def switchOn: F[Unit] = activity.setActivity(act.room, act.name)
      override def switchOff: F[Unit] = onSwitchUpdate(SwitchKey(device, name))
    }

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
