package io.janstenpickle.controller.events.kafka

import java.time.Instant

import cats.Applicative
import cats.data.Chain
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.instances.long._
import cats.instances.map._
import cats.syntax.eq._
import cats.syntax.flatMap._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka._
import fs2.{Chunk, Pull, Stream}
import io.janstenpickle.controller.events.{Event, EventSubscriber}
import org.apache.kafka.common.TopicPartition

object KafkaSubscriber {
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer, V](
    topic: NonEmptyString,
    settings: F[ConsumerSettings[F, Option[V], Option[V]]]
  ): Resource[F, EventSubscriber[F, V]] = {
    def accumulate(
      consumer: KafkaConsumer[F, Option[V], Option[V]]
    ): Stream[F, CommittableConsumerRecord[F, Option[V], Option[V]]] = {
      def go(
        offsets: Map[TopicPartition, Long],
        seenOffsets: Map[TopicPartition, Long],
        state: Chain[Chunk[CommittableConsumerRecord[F, Option[V], Option[V]]]],
        upToDate: Boolean,
      )(
        stream: Stream[F, CommittableConsumerRecord[F, Option[V], Option[V]]]
      ): Pull[F, Chunk[CommittableConsumerRecord[F, Option[V], Option[V]]], Unit] = {
        def chunkOffsets(
          recordChunk: Chunk[CommittableConsumerRecord[F, Option[V], Option[V]]]
        ): Map[TopicPartition, Long] =
          recordChunk.foldLeft(Map.empty[TopicPartition, Long]) {
            case (acc, record) =>
              val state = acc ++ record.offset.offsets.mapValues(_.offset())
              if (state === seenOffsets) return state
              else state
          }

        stream.pull.uncons.flatMap {
          case Some((recordChunk, next)) =>
            if (upToDate) {
              Pull.output1(recordChunk) >> go(offsets, seenOffsets, state, upToDate)(next)
            } else {
              val newSeenOffsets = chunkOffsets(recordChunk)

              Pull.eval(if (offsets.isEmpty) consumerOffsets else Applicative[F].pure(offsets)).flatMap { endOffsets =>
                val done = endOffsets.forall {
                  case (tp, offset) => newSeenOffsets.get(tp).fold(false)(_ >= offset)
                }

                if (done)
                  Pull.output(Chunk.chain(state.append(recordChunk))) >> go(endOffsets, Map.empty, Chain.empty, done)(
                    next
                  )
                else go(endOffsets, newSeenOffsets, state.append(recordChunk), done)(next)
              }
            }
          case None => Pull.output(Chunk.chain(state)) >> Pull.done
        }
      }

      def consumerOffsets: F[Map[TopicPartition, Long]] = consumer.assignment.flatMap(consumer.endOffsets)

      Stream
        .eval(consumerOffsets)
        .flatMap(go(_, Map.empty, Chain.empty, upToDate = false)(consumer.stream).stream)
        .flatMap(Stream.chunk)
    }

    for {
      s <- Resource.liftF(settings)
      consumer <- consumerResource(s)
      _ <- Resource.liftF(consumer.subscribeTo(topic.value))
    } yield
      new EventSubscriber[F, V] {
        override def subscribeEvent: Stream[F, Event[V]] =
          accumulate(consumer).map { r =>
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
          }.unNone
      }
  }

  def stream[F[_]: ConcurrentEffect: ContextShift: Timer, V](
    topic: NonEmptyString,
    settings: F[ConsumerSettings[F, Option[V], Option[V]]]
  ): EventSubscriber[F, V] =
    new EventSubscriber[F, V] {
      override def subscribeEvent: Stream[F, Event[V]] =
        Stream
          .eval(settings)
          .flatMap(consumerStream(_))
          .evalTap(_.subscribeTo(topic.value))
          .flatMap(_.stream)
          .map { r =>
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
          }
          .unNone
    }

}
