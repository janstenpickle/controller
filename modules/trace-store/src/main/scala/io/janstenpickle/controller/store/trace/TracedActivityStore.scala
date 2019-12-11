package io.janstenpickle.controller.store.trace

import cats.Monad
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.store.ActivityStore
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

object TracedActivityStore {
  def apply[F[_]: Monad](store: ActivityStore[F], `type`: String, extraFields: (String, TraceValue)*)(
    implicit trace: Trace[F]
  ): ActivityStore[F] = new ActivityStore[F] {
    def fields(f: (String, TraceValue)*): Seq[(String, TraceValue)] =
      (f ++ extraFields) :+ ("store.type" -> StringValue(`type`))

    override def storeActivity(room: Room, name: NonEmptyString): F[Unit] = trace.span("activity.store") {
      trace.put(fields("room" -> room.value, "activity" -> name.value): _*) *> store.storeActivity(room, name)
    }
    override def loadActivity(room: Room): F[Option[NonEmptyString]] = trace.span("activity.load") {
      trace.put(fields("room" -> room.value): _*) *> store.loadActivity(room).flatTap { activity =>
        trace.put("activity.exists" -> activity.isDefined)
      }
    }
  }
}
