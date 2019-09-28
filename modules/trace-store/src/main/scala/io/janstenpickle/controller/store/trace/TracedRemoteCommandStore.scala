package io.janstenpickle.controller.store.trace

import cats.Monad
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.store.RemoteCommandStore
import natchez.{Trace, TraceValue}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.janstenpickle.controller.model.RemoteCommand

object TracedRemoteCommandStore {
  def apply[F[_]: Monad, T](store: RemoteCommandStore[F, T], `type`: String, extraFields: (String, TraceValue)*)(
    implicit trace: Trace[F]
  ): RemoteCommandStore[F, T] =
    new RemoteCommandStore[F, T] {
      private def span[A](name: String, remote: NonEmptyString, device: NonEmptyString, command: NonEmptyString)(
        k: F[A]
      ): F[A] = trace.span(name) {
        trace.put(
          Seq[(String, TraceValue)](
            "remote" -> remote.value,
            "device" -> device.value,
            "command" -> command.value,
            "store.type" -> `type`
          ) ++ extraFields: _*
        ) *> k
      }

      override def storeCommand(
        remote: NonEmptyString,
        device: NonEmptyString,
        name: NonEmptyString,
        payload: T
      ): F[Unit] = span("storeCommand", remote, device, name) {
        store.storeCommand(remote, device, name, payload)
      }

      override def loadCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Option[T]] =
        span("loadCommand", remote, device, name) {
          store.loadCommand(remote, device, name).flatMap { command =>
            trace.put("command.exists" -> command.isDefined).as(command)
          }
        }

      override def listCommands: F[List[RemoteCommand]] = trace.span("listCommands") {
        store.listCommands.flatMap { commands =>
          trace.put("commands" -> commands.size).as(commands)
        }
      }
    }
}
