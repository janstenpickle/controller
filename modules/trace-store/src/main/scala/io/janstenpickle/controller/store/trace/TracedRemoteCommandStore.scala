package io.janstenpickle.controller.store.trace

import cats.Monad
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{RemoteCommandKey, RemoteCommandSource}
import io.janstenpickle.controller.store.RemoteCommandStore
import natchez.{Trace, TraceValue}

object TracedRemoteCommandStore {
  def apply[F[_]: Monad, T](store: RemoteCommandStore[F, T], `type`: String, extraFields: (String, TraceValue)*)(
    implicit trace: Trace[F]
  ): RemoteCommandStore[F, T] =
    new RemoteCommandStore[F, T] {
      private def span[A](
        name: String,
        source: Option[RemoteCommandSource],
        device: NonEmptyString,
        command: NonEmptyString
      )(k: F[A]): F[A] =
        trace.span(name) {
          trace.put(
            Seq[(String, TraceValue)]("device" -> device.value, "command" -> command.value, "store.type" -> `type`) ++ source.toSeq
              .flatMap { src =>
                Seq[(String, TraceValue)]("source.name" -> src.name.value, "source.type" -> src.`type`.value)
              } ++ extraFields: _*
          ) *> k
        }

      override def storeCommand(device: NonEmptyString, name: NonEmptyString, payload: T): F[Unit] =
        span("macro.store.command", None, device, name) {
          store.storeCommand(device, name, payload)
        }

      override def loadCommand(
        source: Option[RemoteCommandSource],
        device: NonEmptyString,
        name: NonEmptyString
      ): F[Option[T]] =
        span("macro.load.command", source, device, name) {
          store.loadCommand(source, device, name).flatTap { command =>
            trace.put("command.exists" -> command.isDefined)
          }
        }

      override def listCommands: F[List[RemoteCommandKey]] = trace.span("macro.list.commands") {
        store.listCommands.flatTap { commands =>
          trace.put("commands" -> commands.size)
        }
      }
    }
}
