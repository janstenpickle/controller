package io.janstenpickle.controller.`macro`

import cats.Monad
import cats.data.NonEmptyList
import cats.effect.Timer
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.store.MacroStore
import io.janstenpickle.controller.switch.Switches

import scala.concurrent.duration._

class Macro[F[_]: Monad](macroStore: MacroStore[F], remotes: RemoteControls[F], switches: Switches[F])(
  implicit timer: Timer[F],
  errors: MacroErrors[F]
) {
  private def getOrError[A](key: NonEmptyString, map: Map[NonEmptyString, A], missing: F[Unit])(
    f: A => F[Unit]
  ): F[Unit] =
    map.get(key) match {
      case None => missing
      case Some(a) => f(a)
    }

  def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
    listMacros.flatMap { macros =>
      if (macros.contains(name)) errors.macroAlreadyExists(name) else macroStore.storeMacro(name, commands)
    }

  def executeMacro(name: NonEmptyString): F[Unit] =
    macroStore.loadMacro(name).flatMap[Unit] {
      case None => errors.missingMacro(name)
      case Some(commands) =>
        commands
          .traverse[F, Unit] {
            case Command.Remote(remote, device, n) => remotes.send(remote, device, n)
            case Command.Sleep(millis) => timer.sleep(millis.milliseconds)
            case Command.ToggleSwitch(device, switch) => switches.toggle(device, switch)
            case Command.SwitchOn(device, switch) => switches.switchOn(device, switch)
            case Command.SwitchOff(device, switch) => switches.switchOff(device, switch)
            case Command.Macro(n) =>
              if (n == name) ().pure
              else executeMacro(n)
          }
          .void
    }

  def listMacros: F[List[NonEmptyString]] = macroStore.listMacros
}
