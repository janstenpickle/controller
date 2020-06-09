package io.janstenpickle.controller.event.remotecontrol

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.{CommandEvent, RemoteEvent}
import io.janstenpickle.controller.model.{Command, RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.{RemoteControlErrors, RemoteControls}

import scala.concurrent.duration._
import cats.syntax.flatMap._
import cats.syntax.applicativeError._

import scala.concurrent.TimeoutException
import io.janstenpickle.controller.events.syntax.all._

object EventDrivenRemoteControls {
  def apply[F[_]: Concurrent: Timer](
    eventListener: EventSubscriber[F, RemoteEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    commandTimeout: FiniteDuration
  )(implicit errors: RemoteControlErrors[F]): Resource[F, RemoteControls[F]] = {

    def listen(commands: Ref[F, Set[RemoteCommand]], remotes: Ref[F, Set[NonEmptyString]]) =
      eventListener.subscribe.evalMap {
        case RemoteEvent.RemoteLearntCommand(remoteName, remoteDevice, commandSource, command) =>
          commands.update(_ + RemoteCommand(remoteName, commandSource, remoteDevice, command))
        case RemoteEvent.RemoteAddedEvent(remoteName, _) =>
          remotes.update(_ + remoteName)
        case RemoteEvent.RemoteRemovedEvent(remoteName, _) =>
          remotes.update(_ - remoteName)
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
          doIfPresent(remote)(waitFor(CommandEvent.MacroCommand(Command.Remote(remote, commandSource, device, name))) {
            case RemoteEvent.RemoteSendCommandEvent(RemoteCommand(r, c, d, n)) =>
              r == remote && c == commandSource && d == device && n == name
          }.flatMap {
            case None =>
              timeout(
                s"Timed out sending remote control command '$name' for device '$device' on '$remote' with source $commandSource"
              )
            case Some(_) => Applicative[F].unit
          })

        override def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
          doIfPresent(remote)(waitFor(CommandEvent.RemoteLearnCommand(remote, device, name)) {
            case RemoteEvent.RemoteLearntCommand(r, d, None, n) => r == remote && d == device && n == name
          }.flatMap {
            case None => timeout(s"Timed out learning remote control command '$name' for device '$device' on '$remote'")
            case Some(_) => Applicative[F].unit
          })

        override def listCommands: F[List[RemoteCommand]] = commands.get.map(_.toList)

        override def provides(remote: NonEmptyString): F[Boolean] = remotes.get.map(_.contains(remote))
      }
  }
}
