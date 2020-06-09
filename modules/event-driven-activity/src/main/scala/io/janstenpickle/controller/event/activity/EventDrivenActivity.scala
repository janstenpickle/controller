package io.janstenpickle.controller.event.activity

import cats.Applicative
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.model.event.{ActivityUpdateEvent, CommandEvent}

import scala.concurrent.duration._

object EventDrivenActivity {
  def apply[F[_]: Concurrent: Timer](
    activityUpdates: EventSubscriber[F, ActivityUpdateEvent],
    commandPublisher: EventPublisher[F, CommandEvent]
  ): Resource[F, Activity[F]] = {

    def listen(current: MapRef[F, Room, Option[NonEmptyString]]) =
      activityUpdates.subscribe.evalMap {
        case ActivityUpdateEvent(room, name, None) => current(room).set(Some(name))
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
        .liftF[F, MapRef[F, Room, Option[NonEmptyString]]](MapRef.ofConcurrentHashMap[F, Room, NonEmptyString])
      _ <- listener(current)
    } yield
      new Activity[F] {
        override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
          commandPublisher.publish1(CommandEvent.ActivityCommand(room, name))

        override def getActivity(room: Room): F[Option[NonEmptyString]] = current(room).get
      }
  }
}
