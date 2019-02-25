package io.janstenpickle.controller.view

import cats.Monad
import cats.data.NonEmptyList
import cats.effect.Timer
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.remotecontrol.RemoteControl
import io.janstenpickle.controller.store.Store
import io.janstenpickle.controller.switch.{State, Switch}

import scala.concurrent.duration._

class View[F[_]: Monad](
  remotes: Map[NonEmptyString, RemoteControl[F]],
  store: Store[F],
  config: ConfigSource[F],
  switches: Map[NonEmptyString, Switch[F]]
)(implicit timer: Timer[F], errors: ViewErrors[F]) {
  private def getOrUnit[A](key: NonEmptyString, map: Map[NonEmptyString, A])(f: A => F[Unit]): F[Unit] =
    map.get(key) match {
      case None => ().pure
      case Some(a) => f(a)
    }

  def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
    store.storeMacro(name, commands)

  def executeMacro(name: NonEmptyString): F[Unit] =
    store.loadMacro(name).flatMap[Unit] {
      case None => errors.missingMacro(name)
      case Some(commands) =>
        commands
          .traverse[F, Unit] {
            case Command.RemoteCommand(remote, device, n) =>
              getOrUnit(remote, remotes)(_.sendCommand(device, n))
            case Command.Sleep(millis) => timer.sleep(millis.milliseconds)
            case Command.ToggleSwitch(switch) =>
              getOrUnit(switch, switches)(_.toggle)
            case Command.MacroCommand(n) =>
              if (n == name) ().pure
              else executeMacro(n)
          }
          .map(_ => ())
    }

  def send(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
    remotes.get(remote).fold[F[Unit]](errors.missingRemote(remote))(_.sendCommand(device, name))

  def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
    remotes.get(remote).fold[F[Unit]](errors.missingRemote(remote))(_.learn(device, name))

  def switchOn(switch: NonEmptyString): F[Unit] =
    switches.get(switch).fold[F[Unit]](errors.missingSwitch(switch))(_.switchOn)

  def switchOff(switch: NonEmptyString): F[Unit] =
    switches.get(switch).fold[F[Unit]](errors.missingSwitch(switch))(_.switchOff)

  def toggle(switch: NonEmptyString): F[Unit] =
    switches.get(switch).fold[F[Unit]](errors.missingSwitch(switch))(_.toggle)

  def getState(switch: NonEmptyString): F[State] =
    switches.get(switch).fold[F[State]](errors.missingSwitch(switch))(_.getState)

  def setActivity(name: NonEmptyString): F[Unit] =
    config.getActivities.map(_.get(name)).flatMap {
      case None => errors.missingActivity(name)
      case Some(activity) =>
        store.storeActivity(activity.name) *>
          executeMacro(activity.name)
    }

  def getActivity: F[Option[NonEmptyString]] = store.loadActivity
}
