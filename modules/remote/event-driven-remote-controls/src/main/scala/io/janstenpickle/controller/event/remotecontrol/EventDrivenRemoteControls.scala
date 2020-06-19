package io.janstenpickle.controller.event.remotecontrol

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.syntax.all._
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.{CommandEvent, RemoteEvent}
import io.janstenpickle.controller.model.{Command, RemoteCommand, RemoteCommandSource, Room}
import io.janstenpickle.controller.remotecontrol.{RemoteControlErrors, RemoteControls}
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

object EventDrivenRemoteControls {
  def apply[F[_]: Concurrent: Timer, G[_]](
    eventListener: EventSubscriber[F, RemoteEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    source: String,
    commandTimeout: FiniteDuration
  )(
    implicit errors: RemoteControlErrors[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, RemoteControls[F]] = {

    def span[A](name: String, remoteName: NonEmptyString, extraFields: (String, TraceValue)*)(k: F[A]): F[A] =
      trace.span(name) {
        trace.put(extraFields :+ "remote.name" -> StringValue(remoteName.value): _*) *> k
      }

    def listen(commands: Ref[F, Set[RemoteCommand]], remotes: Ref[F, Set[NonEmptyString]]) =
      eventListener.filterEvent(_.source != source).subscribeEvent.evalMapTrace("receive.remote.event") {
        case RemoteEvent.RemoteLearntCommand(remoteName, remoteDevice, commandSource, command) =>
          span(
            "remotes.learnt.command",
            remoteName,
            "remote.device" -> remoteDevice.value,
            "remote.command" -> command.value
          ) {
            commands.update(_ + RemoteCommand(remoteName, commandSource, remoteDevice, command))
          }
        case RemoteEvent.RemoteAddedEvent(remoteName, _) =>
          span("remotes.added", remoteName) {
            remotes.update(_ + remoteName)
          }
        case RemoteEvent.RemoteRemovedEvent(remoteName, _) =>
          span("remotes.removed", remoteName) {
            remotes.update(_ - remoteName) >> commands.update(_.filter(_.remote != remoteName))
          }
        case _ => Applicative[F].unit
      }

    def listener(commands: Ref[F, Set[RemoteCommand]], remotes: Ref[F, Set[NonEmptyString]]): Resource[F, F[Unit]] =
      Stream
        .retry(listen(commands, remotes).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue)
        .compile
        .drain
        .background

    def waitFor(event: CommandEvent)(selector: PartialFunction[RemoteEvent, Boolean]): F[Option[Unit]] =
      eventListener.waitFor(commandPublisher.publish1(event), commandTimeout)(selector)

    for {
      commands <- Resource.liftF(Ref.of[F, Set[RemoteCommand]](Set.empty))
      remotes <- Resource.liftF(Ref.of[F, Set[NonEmptyString]](Set.empty))
      _ <- listener(commands, remotes)
    } yield
      new RemoteControls[F] {
        private def doIfPresent[A](remote: NonEmptyString)(fa: F[A]): F[A] = remotes.get.flatMap { rs =>
          if (rs.contains(remote)) fa
          else errors.missingRemote[A](remote)
        }

        private def timeout(message: String): F[Unit] = new TimeoutException(message).raiseError[F, Unit]

        override def send(
          remote: NonEmptyString,
          commandSource: Option[RemoteCommandSource],
          device: NonEmptyString,
          name: NonEmptyString
        ): F[Unit] =
          doIfPresent(remote)(
            span("remotes.send", remote, "remote.device" -> device.value, "remote.command" -> name.value) {

              waitFor(CommandEvent.MacroCommand(Command.Remote(remote, commandSource, device, name))) {
                case RemoteEvent.RemoteSentCommandEvent(RemoteCommand(r, c, d, n)) =>
                  r == remote && c == commandSource && d == device && n == name
              }
            }.flatMap {
              case None =>
                timeout(
                  s"Timed out sending remote control command '$name' for device '$device' on '$remote' with source $commandSource"
                )
              case Some(_) => Applicative[F].unit
            }
          )

        override def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
          doIfPresent(remote)(waitFor(CommandEvent.RemoteLearnCommand(remote, device, name)) {
            case RemoteEvent.RemoteLearntCommand(r, d, None, n) => r == remote && d == device && n == name
          }.flatMap {
            case None => timeout(s"Timed out learning remote control command '$name' for device '$device' on '$remote'")
            case Some(_) => Applicative[F].unit
          })

        override def listCommands: F[List[RemoteCommand]] =
          trace.span("remotes.list.commands") {
            commands.get.map(_.toList)
          }

        override def provides(remote: NonEmptyString): F[Boolean] =
          span("remotes.provides", remote) {
            remotes.get.map(_.contains(remote))
          }
      }
  }
}
