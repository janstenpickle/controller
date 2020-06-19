package io.janstenpickle.controller.`macro`

import cats.{Applicative, Monad}
import cats.data.NonEmptyList
import cats.effect.Timer
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.{Command, RemoteCommand, SwitchKey}
import io.janstenpickle.controller.model.event.MacroEvent
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.`macro`.store.MacroStore
import io.janstenpickle.controller.switch.Switches
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

import scala.concurrent.duration._

trait Macro[F[_]] {
  def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit]
  def executeMacro(name: NonEmptyString): F[Unit]
  def executeCommand(command: Command): F[Unit]
  def maybeExecuteMacro(name: NonEmptyString): F[Unit]
  def listMacros: F[List[NonEmptyString]]
}

object Macro {
  private abstract class BaseMacro[F[_]](
    macroStore: MacroStore[F],
    remotes: RemoteControls[F],
    switches: Switches[F],
    publisher: EventPublisher[F, MacroEvent]
  )(implicit F: Monad[F], timer: Timer[F], errors: MacroErrors[F], trace: Trace[F])
      extends Macro[F] {
    def span[A](name: String, macroName: NonEmptyString, extraFields: (String, TraceValue)*)(k: F[A]): F[A] =
      trace.span[A](name) { trace.put(extraFields :+ "macro.name" -> StringValue(macroName.value): _*) *> k }

    def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
      span("store.macro", name, "commands" -> commands.size) {
        listMacros.flatMap { macros =>
          if (macros.contains(name))
            trace.put("error" -> true, "reason" -> "macro already exists") *> errors.macroAlreadyExists(name)
          else macroStore.storeMacro(name, commands) *> publisher.publish1(MacroEvent.StoredMacroEvent(name, commands))
        }
      }

    def executeCommand(command: Command): F[Unit] =
      (command match {
        case Command.Remote(remote, commandSource, device, n) => remotes.send(remote, commandSource, device, n)
        case Command.Sleep(millis) => timer.sleep(millis.milliseconds)
        case Command.ToggleSwitch(device, switch) => switches.toggle(device, switch)
        case Command.SwitchOn(device, switch) => switches.switchOn(device, switch)
        case Command.SwitchOff(device, switch) => switches.switchOff(device, switch)
        case Command.Macro(n) => executeMacro(n)
      }) *> publisher.publish1(MacroEvent.ExecutedCommand(command))

    protected def execute(name: NonEmptyString)(commands: NonEmptyList[Command]): F[Unit] =
      span("execute", name, "commands" -> commands.size) {
        commands.traverse {
          case Command.Macro(n) if n == name => F.unit
          case command => executeCommand(command)
        }.void *> publisher.publish1(MacroEvent.ExecutedMacro(name))
      }

    def executeMacro(name: NonEmptyString): F[Unit] = span("execute.macro", name) {
      macroStore
        .loadMacro(name)
        .flatMap[Unit](
          _.fold[F[Unit]](trace.put("error" -> true, "reason" -> "macro not found") *> errors.missingMacro(name))(
            execute(name)
          )
        )
    }

    def maybeExecuteMacro(name: NonEmptyString): F[Unit] = span("maybe.execute.macro", name) {
      macroStore
        .loadMacro(name)
        .flatMap(
          _.fold(trace.put("macro.exists" -> false))(cmds => trace.put("macro.exists" -> true) *> execute(name)(cmds))
        )
    }

    def listMacros: F[List[NonEmptyString]] = trace.span("list.macros") { macroStore.listMacros }

  }

  def apply[F[_]](
    macroStore: MacroStore[F],
    remotes: RemoteControls[F],
    switches: Switches[F],
    publisher: EventPublisher[F, MacroEvent]
  )(implicit F: Monad[F], timer: Timer[F], errors: MacroErrors[F], trace: Trace[F]): Macro[F] =
    new BaseMacro[F](macroStore, remotes, switches, publisher) {}

  def conditional[F[_]](
    macroStore: MacroStore[F],
    remotes: RemoteControls[F],
    switches: Switches[F],
    publisher: EventPublisher[F, MacroEvent]
  )(implicit F: Monad[F], timer: Timer[F], errors: MacroErrors[F], trace: Trace[F]): Macro[F] =
    new BaseMacro[F](macroStore, remotes, switches, publisher) {
      override def executeMacro(name: NonEmptyString): F[Unit] =
        macroStore.loadMacro(name).flatMap {
          case None => F.unit
          case Some(_) => super.executeMacro(name)
        }

      override def executeCommand(command: Command): F[Unit] =
        (command match {
          case Command.Remote(remote, commandSource, device, n) =>
            remotes
              .provides(remote)
              .ifM(remotes.listCommands.map(_.contains(RemoteCommand(remote, commandSource, device, n))), F.pure(false))
          case Command.Sleep(_) => F.pure(true)
          case Command.ToggleSwitch(device, switch) =>
            switches.list.map(_.contains(SwitchKey(device, switch)))
          case Command.SwitchOn(device, switch) =>
            switches.switchOn(device, switch)
            switches.list.map(_.contains(SwitchKey(device, switch)))
          case Command.SwitchOff(device, switch) =>
            switches.switchOff(device, switch)
            switches.list.map(_.contains(SwitchKey(device, switch)))
          case Command.Macro(name) => macroStore.loadMacro(name).map(_.isDefined)
        }).ifM(super.executeCommand(command), F.unit)

      override protected def execute(name: NonEmptyString)(commands: NonEmptyList[Command]): F[Unit] =
        commands
          .traverse {
            case Command.Remote(remote, commandSource, device, n) =>
              remotes
                .provides(remote)
                .ifM(
                  remotes.listCommands.map(_.contains(RemoteCommand(remote, commandSource, device, n))),
                  F.pure(false)
                )
            case Command.Sleep(_) => F.pure(true)
            case Command.ToggleSwitch(device, switch) =>
              switches.list.map(_.contains(SwitchKey(device, switch)))
            case Command.SwitchOn(device, switch) =>
              switches.switchOn(device, switch)
              switches.list.map(_.contains(SwitchKey(device, switch)))
            case Command.SwitchOff(device, switch) =>
              switches.switchOff(device, switch)
              switches.list.map(_.contains(SwitchKey(device, switch)))
            case Command.Macro(n) => macroStore.loadMacro(n).map(_.isDefined)
          }
          .map(_.forall(identity))
          .ifM(super.execute(name)(commands), F.unit)

    }
}
