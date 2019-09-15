package io.janstenpickle.controller.`macro`

import cats.Monad
import cats.data.NonEmptyList
import cats.effect.Timer
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.store.MacroStore
import io.janstenpickle.controller.switch.Switches
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

import scala.concurrent.duration._

trait Macro[F[_]] {
  def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit]
  def executeMacro(name: NonEmptyString): F[Unit]
  def maybeExecuteMacro(name: NonEmptyString): F[Unit]
  def listMacros: F[List[NonEmptyString]]
}

object Macro {
  def apply[F[_]](
    macroStore: MacroStore[F],
    remotes: RemoteControls[F],
    switches: Switches[F]
  )(implicit F: Monad[F], timer: Timer[F], errors: MacroErrors[F], trace: Trace[F]): Macro[F] = new Macro[F] {
    def span[A](name: String, macroName: NonEmptyString, extraFields: (String, TraceValue)*)(k: F[A]): F[A] =
      trace.span[A](name) { trace.put(extraFields :+ "macro.name" -> StringValue(macroName.value): _*) *> k }

    def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
      span("storeMacro", name, "commands" -> commands.size) {
        listMacros.flatMap { macros =>
          if (macros.contains(name))
            trace.put("error" -> true, "reason" -> "macro already exists") *> errors.macroAlreadyExists(name)
          else macroStore.storeMacro(name, commands)
        }
      }

    private def execute(name: NonEmptyString)(commands: NonEmptyList[Command]): F[Unit] =
      span("execute", name, "commands" -> commands.size) {
        commands.traverse {
          case Command.Remote(remote, device, n) => span("macroRemote", name) { remotes.send(remote, device, n) }
          case Command.Sleep(millis) => timer.sleep(millis.milliseconds)
          case Command.ToggleSwitch(device, switch) => switches.toggle(device, switch)
          case Command.SwitchOn(device, switch) => switches.switchOn(device, switch)
          case Command.SwitchOff(device, switch) => switches.switchOff(device, switch)
          case Command.Macro(n) =>
            if (n == name) ().pure
            else executeMacro(n)
        }.void
      }

    def executeMacro(name: NonEmptyString): F[Unit] = span("executeMacro", name) {
      macroStore
        .loadMacro(name)
        .flatMap[Unit](
          _.fold[F[Unit]](trace.put("error" -> true, "reason" -> "macro not found") *> errors.missingMacro(name))(
            execute(name)
          )
        )
    }

    def maybeExecuteMacro(name: NonEmptyString): F[Unit] =
      macroStore.loadMacro(name).flatMap(_.fold(F.unit)(execute(name)))

    def listMacros: F[List[NonEmptyString]] = macroStore.listMacros
  }
}
