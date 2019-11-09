package io.janstenpickle.controller.remotecontrol

import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Apply, FlatMap}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.controller.store.RemoteCommandStore
import natchez.{Trace, TraceValue}

trait RemoteControl[F[_]] {
  def learn(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def sendCommand(source: Option[RemoteCommandSource], device: NonEmptyString, name: NonEmptyString): F[Unit]
  def listCommands: F[List[RemoteCommand]]
}

object RemoteControl {
  def traced[F[_]: Apply](remoteControl: RemoteControl[F], extraFields: (String, TraceValue)*)(
    implicit trace: Trace[F]
  ): RemoteControl[F] =
    new RemoteControl[F] {
      private def traceInfo(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        trace.put(extraFields ++ Seq[(String, TraceValue)]("device" -> device.value, "command" -> name.value): _*)

      override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] = trace.span("remoteControlLearn") {
        traceInfo(device, name) *> remoteControl.learn(device, name)
      }

      override def sendCommand(
        source: Option[RemoteCommandSource],
        device: NonEmptyString,
        name: NonEmptyString
      ): F[Unit] =
        trace.span("remoteControlSendCommand") {
          traceInfo(device, name) *> remoteControl.sendCommand(source: Option[RemoteCommandSource], device, name)
        }

      override def listCommands: F[List[RemoteCommand]] = trace.span("remoteControlListCommands") {
        trace.put(extraFields: _*) *> remoteControl.listCommands
      }
    }

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
  }

  def apply[F[_]: FlatMap, T](
    remote: Remote[F, T],
    store: RemoteCommandStore[F, T]
  )(implicit errors: RemoteControlErrors[F], trace: Trace[F]): RemoteControl[F] =
    traced(
      new RemoteControl[F] {

        override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
          remote.learn.flatMap {
            case None =>
              trace.put("error" -> true, "reason" -> "learn failure") *> errors.learnFailure(remote.name, device, name)
            case Some(payload) => store.storeCommand(device, name, payload)
          }

        override def sendCommand(
          source: Option[RemoteCommandSource],
          device: NonEmptyString,
          name: NonEmptyString
        ): F[Unit] =
          store.loadCommand(source, device, name).flatMap {
            case None =>
              trace.put("error" -> true, "reason" -> "command not found") *> errors
                .commandNotFound(remote.name, device, name)
            case Some(payload) => remote.sendCommand(payload)
          }

        override def listCommands: F[List[RemoteCommand]] =
          store.listCommands.map(_.map { k =>
            RemoteCommand(remote.name, k.source, k.device, k.name)
          })
      },
      "remote" -> remote.name.value
    )
}
