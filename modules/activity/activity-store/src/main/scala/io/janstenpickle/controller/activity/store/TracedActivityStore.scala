package io.janstenpickle.controller.activity.store

import cats.Monad
import cats.syntax.apply._
import cats.syntax.flatMap._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Room
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue

object TracedActivityStore {
  def apply[F[_]: Monad](store: ActivityStore[F], `type`: String, extraFields: (String, AttributeValue)*)(
    implicit trace: Trace[F]
  ): ActivityStore[F] = new ActivityStore[F] {
    def fields(f: (String, AttributeValue)*): Seq[(String, AttributeValue)] =
      (f ++ extraFields) :+ ("store.type" -> StringValue(`type`))

    override def storeActivity(room: Room, name: NonEmptyString): F[Unit] = trace.span("activity.store") {
      trace.putAll(fields("room" -> room.value, "activity" -> name.value): _*) *> store.storeActivity(room, name)
    }
    override def loadActivity(room: Room): F[Option[NonEmptyString]] = trace.span("activity.load") {
      trace.putAll(fields("room" -> room.value): _*) *> store.loadActivity(room).flatTap { activity =>
        trace.put("activity.exists", activity.isDefined)
      }
    }
  }
}
