package io.janstenpickle.controller.events.kafka

import java.time.Instant

import cats.Applicative
import cats.effect.concurrent.Deferred
import cats.effect.syntax.concurrent._
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import cats.instances.long._
import cats.instances.map._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.concurrent.Queue
import fs2.kafka._
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.events.{Event, EventSubscriber}
import org.apache.kafka.common.TopicPartition

import scala.concurrent.duration._

object KafkaSubscriber {
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer, V](
    topic: NonEmptyString,
    settings: F[ConsumerSettings[F, Option[V], Option[V]]],
    headerMatchesFilter: Map[String, String],
    headerNonMatchesFilter: Map[String, String]
  ): Resource[F, EventSubscriber[F, V]] = Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
    def accumulate(
      consumer: KafkaConsumer[F, Option[V], Option[V]]
    ): Resource[F, Queue[F, CommittableConsumerRecord[F, Option[V], Option[V]]]] = {
      def fulfillPromise(
        deferred: Deferred[F, Boolean]
      ): Pipe[F, CommittableConsumerRecord[F, Option[V], Option[V]], Unit] =
        _.chunks
          .evalMapAccumulate((Map.empty[TopicPartition, Long], false)) {
            case ((seenOffsets, complete), recordChunk) =>
              if (complete) Applicative[F].pure(((Map.empty[TopicPartition, Long], complete), ()))
              else
                consumer.assignment.flatMap(consumer.endOffsets).flatMap { topicOffsets =>
                  def chunkOffsets: Map[TopicPartition, Long] =
                    recordChunk.foldLeft(seenOffsets) {
                      case (acc, record) =>
                        val state = acc ++ record.offset.offsets.mapValues(_.offset())
                        if (state === topicOffsets) return state
                        else state
                    }

                  val newSeenOffsets = chunkOffsets
                  val done = topicOffsets.forall {
                    case (tp, offset) => newSeenOffsets.get(tp).fold(false)(_ >= offset)
                  }

                  if (done)
                    deferred
                      .complete(done)
                      .handleErrorWith(
                        logger.warn(_)("Failed to complete deferred, it may have already been completed")
                      )
                      .as(((chunkOffsets, done), ()))
                  else Applicative[F].pure(((chunkOffsets, done), ()))
                }
          }
          .map(_._2)

      def waitForMessage(
        deferred: Deferred[F, Boolean]
      ): Pipe[F, CommittableConsumerRecord[F, Option[V], Option[V]], Unit] =
        in =>
          Stream
            .eval(Timer[F].sleep(5.seconds).as(None))
            .merge(in.map(Some(_)))
            .evalMapAccumulate(false) {
              case (false, None) => deferred.complete(true).as((true, ()))
              case _ => Applicative[F].pure((true, ()))
            }
            .map(_._2)

      for {
        queue <- Resource.liftF(Queue.unbounded[F, CommittableConsumerRecord[F, Option[V], Option[V]]])
        deferred <- Resource.liftF(Deferred[F, Boolean])
        _ <- consumer.stream
          .broadcastThrough(queue.enqueue, fulfillPromise(deferred), waitForMessage(deferred))
          .compile
          .drain
          .background
        _ <- Resource.liftF(deferred.get)
      } yield queue
    }

    for {
      s <- Resource.liftF(settings)
      consumer <- consumerResource(s)
      _ <- Resource.liftF(consumer.subscribeTo(topic.value))
      queue <- accumulate(consumer)
    } yield
      new EventSubscriber[F, V] {
        override def subscribeEvent: Stream[F, Event[V]] =
          queue.dequeue.map(makeEvent(headerMatchesFilter, headerNonMatchesFilter)).unNone
      }
  }

  def stream[F[_]: ConcurrentEffect: ContextShift: Timer, V](
    topic: NonEmptyString,
    settings: F[ConsumerSettings[F, Option[V], Option[V]]],
    headerMatchesFilter: Map[String, String],
    headerNonMatchesFilter: Map[String, String]
  ): EventSubscriber[F, V] =
    new EventSubscriber[F, V] {
      override def subscribeEvent: Stream[F, Event[V]] =
        Stream
          .eval(settings)
          .flatMap(consumerStream(_))
          .evalTap(_.subscribeTo(topic.value))
          .flatMap(_.stream)
          .map(makeEvent(headerMatchesFilter, headerNonMatchesFilter))
          .unNone
    }

  private def makeEvent[F[_], V](headerMatchesFilter: Map[String, String], headerNonMatchesFilter: Map[String, String])(
    r: CommittableConsumerRecord[F, Option[V], Option[V]]
  ): Option[Event[V]] = {

    val matchesFilter = headerMatchesFilter.forall {
      case (k, v) =>
        r.record.headers.withKey(k).map(_.as[String]).contains(v)
    } && headerNonMatchesFilter.forall {
      case (k, v) =>
        !r.record.headers.withKey(k).map(_.as[String]).contains(v)
    }

    if (matchesFilter)
      r.record.value
        .orElse(r.record.key)
        .map(
          Event(
            _,
            Instant.ofEpochMilli(
              r.record.timestamp.createTime
                .orElse(r.record.timestamp.logAppendTime)
                .orElse(r.record.timestamp.unknownTime)
                .getOrElse(0)
            )
          )
        )
    else None
  }
}
