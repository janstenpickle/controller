package io.janstenpickle.controller.remotecontrol

import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Apply, Monad}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.RemoteEvent
import io.janstenpickle.controller.model.{RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.controller.remote.store.RemoteCommandStore
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanStatus}

trait RemoteControl[F[_]] {
  def remoteName: NonEmptyString
  def supportsLearning: Boolean
  def learn(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def sendCommand(source: Option[RemoteCommandSource], device: NonEmptyString, name: NonEmptyString): F[Unit]
  def listCommands: F[List[RemoteCommand]]
}

object RemoteControl {
  def traced[F[_]: Apply](remoteControl: RemoteControl[F], extraFields: (String, AttributeValue)*)(
    implicit trace: Trace[F]
  ): RemoteControl[F] =
    new RemoteControl[F] {
      private def traceInfo(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        trace.putAll(
          extraFields ++ Seq[(String, AttributeValue)](
            "remote" -> remoteName.value,
            "device" -> device.value,
            "command" -> name.value
          ): _*
        )

      override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] = trace.span("remote.control.learn") {
        traceInfo(device, name) *> remoteControl.learn(device, name)
      }

      override def sendCommand(
        source: Option[RemoteCommandSource],
        device: NonEmptyString,
        name: NonEmptyString
      ): F[Unit] =
        trace.span("remote.control.send.command") {
          traceInfo(device, name) *> remoteControl.sendCommand(source: Option[RemoteCommandSource], device, name)
        }

      override def listCommands: F[List[RemoteCommand]] = trace.span("remote.control.list.commands") {
        trace.putAll(extraFields: _*) *> remoteControl.listCommands
      }

      override def remoteName: NonEmptyString = remoteControl.remoteName

      override def supportsLearning: Boolean = remoteControl.supportsLearning
    }

  def evented[F[_]: Monad](
    underlying: RemoteControl[F],
    eventPublisher: EventPublisher[F, RemoteEvent]
  ): F[RemoteControl[F]] =
    Applicative[F].pure(new RemoteControl[F] {
      override def remoteName: NonEmptyString = underlying.remoteName

      override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.learn(device, name) *> eventPublisher
          .publish1(RemoteEvent.RemoteLearntCommand(remoteName, device, None, name))

      override def sendCommand(
        source: Option[RemoteCommandSource],
        device: NonEmptyString,
        name: NonEmptyString
      ): F[Unit] =
        underlying.sendCommand(source, device, name) *> eventPublisher
          .publish1(RemoteEvent.RemoteSentCommandEvent(RemoteCommand(remoteName, source, device, name)))

      override def listCommands: F[List[RemoteCommand]] = underlying.listCommands

      override def supportsLearning: Boolean = underlying.supportsLearning
    })

  def empty[F[_]](
    remote: NonEmptyString
  )(implicit F: Applicative[F], errors: RemoteControlErrors[F]): RemoteControl[F] = new RemoteControl[F] {
    override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] = errors.learningNotSupported(remote)

    override def sendCommand(
      source: Option[RemoteCommandSource],
      device: NonEmptyString,
      name: NonEmptyString
    ): F[Unit] =
      F.unit

    override def listCommands: F[List[RemoteCommand]] = F.pure(List.empty)

    override def remoteName: NonEmptyString = remote

    override def supportsLearning: Boolean = false
  }

  def apply[F[_]: Monad, T](
    remote: Remote[F, T],
    store: RemoteCommandStore[F, T],
    eventPublisher: EventPublisher[F, RemoteEvent]
  )(implicit errors: RemoteControlErrors[F], trace: Trace[F]): F[RemoteControl[F]] =
    evented(
      traced(new RemoteControl[F] {

        override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
          remote.learn.flatMap {
            case None =>
              trace.setStatus(SpanStatus.Internal("Learn failure")) *> errors.learnFailure(remote.name, device, name)
            case Some(payload) => store.storeCommand(device, name, payload)
          }

        override def sendCommand(
          source: Option[RemoteCommandSource],
          device: NonEmptyString,
          name: NonEmptyString
        ): F[Unit] =
          store.loadCommand(source, device, name).flatMap {
            case None =>
              trace.setStatus(SpanStatus.NotFound) *> errors
                .commandNotFound(remote.name, device, name)
            case Some(payload) => remote.sendCommand(payload)
          }

        override def listCommands: F[List[RemoteCommand]] =
          store.listCommands.map(_.map { k =>
            RemoteCommand(remote.name, k.source, k.device, k.name)
          })

        override def remoteName: NonEmptyString = remote.name

        override def supportsLearning: Boolean = true
      }),
      eventPublisher
    )
}
