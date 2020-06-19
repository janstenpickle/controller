package io.janstenpickle.controller.event.activity

import cats.Applicative
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.syntax.stream._
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.model.event.{ActivityUpdateEvent, CommandEvent}
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

import scala.concurrent.duration._

object EventDrivenActivity {
  def apply[F[_]: Concurrent: Timer, G[_]](
    activityUpdates: EventSubscriber[F, ActivityUpdateEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    source: String,
  )(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, Activity[F]] = {

    def fields[A](room: Room, extraFields: (String, TraceValue)*)(k: F[A]): F[A] =
      trace.put(extraFields :+ "room" -> StringValue(room.value): _*) *> k

    def listen(current: MapRef[F, Room, Option[NonEmptyString]]) =
      activityUpdates.filterEvent(_.source != source).subscribeEvent.evalMapTrace("set.activity") {
        case ActivityUpdateEvent(room, name, None) =>
          fields(room, "activity" -> name.value)(current(room).set(Some(name)))
        case _ => Applicative[F].unit
      }

    def listener(current: MapRef[F, Room, Option[NonEmptyString]]): Resource[F, F[Unit]] =
      Stream
        .retry(listen(current).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue)
        .compile
        .drain
        .background

    for {
      current <- Resource
        .liftF[F, MapRef[F, Room, Option[NonEmptyString]]](MapRef.ofConcurrentHashMap[F, Room, NonEmptyString]())
      _ <- listener(current)
    } yield
      new Activity[F] {
        override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
          trace.span("send.set.activity")(
            fields(room, "activity" -> name.value)(commandPublisher.publish1(CommandEvent.ActivityCommand(room, name)))
          )

        override def getActivity(room: Room): F[Option[NonEmptyString]] =
          trace.span("get.activity")(fields(room)(current(room).get))
      }
  }
}
