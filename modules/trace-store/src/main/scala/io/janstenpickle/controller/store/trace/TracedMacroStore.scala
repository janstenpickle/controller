package io.janstenpickle.controller.store.trace

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.store.MacroStore
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

object TracedMacroStore {
  def apply[F[_]: Monad](store: MacroStore[F], `type`: String, extraFields: (String, TraceValue)*)(
    implicit trace: Trace[F]
  ): MacroStore[F] = new MacroStore[F] {
    def fields(f: (String, TraceValue)*): Seq[(String, TraceValue)] =
      (f ++ extraFields) :+ ("store.type" -> StringValue(`type`))

    override def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] = trace.span("storeMacro") {
      trace.put(fields("name" -> name.value, "commands" -> commands.size): _*) *> store.storeMacro(name, commands)
    }

    override def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]] = trace.span("loadMacro") {
      trace.put(fields("name" -> name.value): _*) *> store.loadMacro(name).flatMap { commands =>
        trace.put("macro.exists" -> commands.isDefined).as(commands)
      }
    }

    override def listMacros: F[List[NonEmptyString]] = trace.span("listMacros") {
      trace.put(fields(): _*) *> store.listMacros.flatMap { macros =>
        trace.put("macros" -> macros.size).as(macros)
      }
    }
  }
}
