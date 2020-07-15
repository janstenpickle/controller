package io.janstenpickle.controller.event.activity

import cats.Applicative
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.cache.{Cache, CacheResource}
import io.janstenpickle.controller.events.syntax.stream._
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.model.event.{ActivityUpdateEvent, CommandEvent}
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue

import scala.concurrent.duration._

object EventDrivenActivity {
  def apply[F[_]: Concurrent: Timer, G[_]](
    activityUpdates: EventSubscriber[F, ActivityUpdateEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    source: String,
    cacheTimeout: FiniteDuration = 20.minutes
  )(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, Activity[F]] = {

    def fields[A](room: Room, extraFields: (String, AttributeValue)*)(k: F[A]): F[A] =
      trace.putAll(extraFields :+ "room" -> StringValue(room.value): _*) *> k

    def listen(current: Cache[F, Room, NonEmptyString]) =
      activityUpdates.filterEvent(_.source != source).subscribeEvent.evalMapTrace("set.activity") {
        case ActivityUpdateEvent(room, name, None) =>
          fields(room, "activity" -> name.value)(current.set(room, name))
        case _ => Applicative[F].unit
      }

    def listener(current: Cache[F, Room, NonEmptyString]): Resource[F, F[Unit]] =
      Stream
        .retry(listen(current).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue)
        .compile
        .drain
        .background

    for {
      current <- CacheResource.caffeine[F, Room, NonEmptyString](cacheTimeout)
      _ <- listener(current)
    } yield
      new Activity[F] {
        override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
          trace.span("send.set.activity")(
            fields(room, "activity" -> name.value)(commandPublisher.publish1(CommandEvent.ActivityCommand(room, name)))
          )

        override def getActivity(room: Room): F[Option[NonEmptyString]] =
          trace.span("get.activity")(fields(room)(current.get(room)))
      }
  }
}
