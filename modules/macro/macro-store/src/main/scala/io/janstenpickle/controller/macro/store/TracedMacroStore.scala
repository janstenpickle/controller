package io.janstenpickle.controller.`macro`.store

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.apply._
import cats.syntax.flatMap._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue

object TracedMacroStore {
  def apply[F[_]: Monad](store: MacroStore[F], `type`: String, extraFields: (String, AttributeValue)*)(
    implicit trace: Trace[F]
  ): MacroStore[F] = new MacroStore[F] {
    def fields(f: (String, AttributeValue)*): Seq[(String, AttributeValue)] =
      (f ++ extraFields) :+ ("store.type" -> StringValue(`type`))

    override def storeMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
      trace.span("store.macro") {
        trace.putAll(fields("name" -> name.value, "commands" -> commands.size): _*) *> store.storeMacro(name, commands)
      }

    override def loadMacro(name: NonEmptyString): F[Option[NonEmptyList[Command]]] = trace.span("load.macro") {
      trace.putAll(fields("name" -> name.value): _*) *> store.loadMacro(name).flatTap { commands =>
        trace.put("macro.exists", commands.isDefined)
      }
    }

    override def listMacros: F[List[NonEmptyString]] = trace.span("list.macros") {
      trace.putAll(fields(): _*) *> store.listMacros.flatTap { macros =>
        trace.put("macros", macros.size)
      }
    }
  }
}
