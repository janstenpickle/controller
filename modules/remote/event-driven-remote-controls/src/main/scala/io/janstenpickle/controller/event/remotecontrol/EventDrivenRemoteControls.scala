package io.janstenpickle.controller.event.remotecontrol

import cats.Applicative
import cats.effect.syntax.concurrent._
import cats.effect.{BracketThrow, Concurrent, Resource, Timer}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.cache.{Cache, CacheResource}
import io.janstenpickle.controller.events.syntax.all._
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.{CommandEvent, RemoteEvent}
import io.janstenpickle.controller.model.{Command, RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.{RemoteControlErrors, RemoteControls}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object EventDrivenRemoteControls {
  def apply[F[_]: Concurrent: Timer, G[_]: BracketThrow](
    eventListener: EventSubscriber[F, RemoteEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    source: String,
    commandTimeout: FiniteDuration,
    learnTimeout: FiniteDuration,
    k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
    cacheTimeout: FiniteDuration = 20.minutes
  )(
    implicit errors: RemoteControlErrors[F],
    trace: Trace[F],
    provide: Provide[G, F, Span[G]]
  ): Resource[F, RemoteControls[F]] = {

    def span[A](name: String, remoteName: NonEmptyString, extraFields: (String, AttributeValue)*)(k: F[A]): F[A] =
      trace.span(name) {
        trace.putAll(extraFields :+ "remote.name" -> StringValue(remoteName.value): _*) *> k
      }

    def listen(commands: Cache[F, NonEmptyString, Set[RemoteCommand]], remotes: Cache[F, NonEmptyString, Boolean]) =
      eventListener.filterEvent(_.source != source).subscribeEvent.evalMapTrace("receive.remote.event", k) {
        case RemoteEvent.RemoteLearntCommand(remoteName, remoteDevice, commandSource, command) =>
          span(
            "remotes.learnt.command",
            remoteName,
            "remote.device" -> remoteDevice.value,
            "remote.command" -> command.value
          ) {
            lazy val rc = RemoteCommand(remoteName, commandSource, remoteDevice, command)
            for {
              rcs <- commands.get(remoteName)
              _ <- commands.set(remoteName, rcs.fold(Set(rc))(_ + rc))
            } yield ()
          }
        case RemoteEvent.RemoteAddedEvent(remoteName, supportsLearning, _) =>
          span("remotes.added", remoteName) {
            remotes.set(remoteName, supportsLearning)
          }
        case RemoteEvent.RemoteRemovedEvent(remoteName, _) =>
          span("remotes.removed", remoteName) {
            remotes.remove(remoteName) >> commands.remove(remoteName)
          }
        case _ => Applicative[F].unit
      }

    def listener(
      commands: Cache[F, NonEmptyString, Set[RemoteCommand]],
      remotes: Cache[F, NonEmptyString, Boolean]
    ): Resource[F, F[Unit]] =
      Stream
        .retry(listen(commands, remotes).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue)
        .compile
        .drain
        .background

    def waitFor(event: CommandEvent, timeout: FiniteDuration = commandTimeout)(
      selector: PartialFunction[RemoteEvent, Boolean]
    ): F[Option[Unit]] =
      eventListener.waitFor(commandPublisher.publish1(event), timeout)(selector)

    for {
      commands <- CacheResource.caffeine[F, NonEmptyString, Set[RemoteCommand]](cacheTimeout)
      remotes <- CacheResource.caffeine[F, NonEmptyString, Boolean](cacheTimeout)
      _ <- listener(commands, remotes)
    } yield
      new RemoteControls[F] {
        private def doIfPresent[A](remote: NonEmptyString)(fa: F[A]): F[A] = remotes.getAll.flatMap { rs =>
          if (rs.contains(remote)) fa
          else errors.missingRemote[A](remote)
        }

        private def timeout(message: String): F[Unit] =
          (new TimeoutException(message) with NoStackTrace).raiseError[F, Unit]

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
          doIfPresent(remote)(waitFor(CommandEvent.RemoteLearnCommand(remote, device, name), learnTimeout) {
            case RemoteEvent.RemoteLearntCommand(r, d, None, n) => r == remote && d == device && n == name
          }.flatMap {
            case None =>
              timeout(s"Timed out learning remote control command '$name' for device '$device' on '$remote'")
            case Some(_) => Applicative[F].unit
          })

        override def listCommands: F[List[RemoteCommand]] =
          trace.span("remotes.list.commands") {
            commands.getAll.map(_.values.toList.flatten)
          }

        override def provides(remote: NonEmptyString): F[Boolean] =
          span("remotes.provides", remote) {
            remotes.get(remote).map(_.isDefined)
          }

        override def listRemotes: F[Set[RemoteControls.RemoteControlDef]] =
          remotes.getAll.map(_.toSet.map((RemoteControls.RemoteControlDef.apply _).tupled))
      }
  }
}
