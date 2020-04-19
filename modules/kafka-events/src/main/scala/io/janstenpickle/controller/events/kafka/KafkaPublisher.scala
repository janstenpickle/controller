package io.janstenpickle.controller.events.kafka

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, ConcurrentEffect, ContextShift, Resource}
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Pipe
import fs2.kafka._
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.ToOption

object KafkaPublisher {
  def apply[F[_]: ConcurrentEffect: ContextShift: Clock, V: ToOption](
    topic: NonEmptyString,
    settings: ProducerSettings[F, V, Option[V]],
    staticHeaders: Map[String, String]
  ): Resource[F, EventPublisher[F, V]] =
    producerResource(settings).map { producer =>
      val headers = Headers.fromIterable(staticHeaders.map { case (k, v) => Header(k, v) })

      new EventPublisher[F, V] {
        override def publish1(a: V): F[Unit] = Clock[F].realTime(TimeUnit.MILLISECONDS).flatMap { time =>
          producer
            .produce(
              ProducerRecords
                .one(ProducerRecord(topic.value, a, ToOption[V].toOption(a)).withTimestamp(time).withHeaders(headers))
            )
            .flatten
            .void
        }

        override def pipe: Pipe[F, V, Unit] =
          _.chunks
            .evalMap { vs =>
              Clock[F].realTime(TimeUnit.MILLISECONDS).map { time =>
                ProducerRecords(vs.map { v =>
                  ProducerRecord(topic.value, v, ToOption[V].toOption(v)).withTimestamp(time).withHeaders(headers)
                })
              }
            }
            .through(produce(settings, producer))
            .map(_ => ())
      }
    }
}
