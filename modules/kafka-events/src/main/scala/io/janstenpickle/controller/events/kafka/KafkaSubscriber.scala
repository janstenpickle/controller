package io.janstenpickle.controller.events.kafka

import java.time.Instant

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
    settings: ConsumerSettings[F, Option[V], Option[V]]
  ): Resource[F, EventSubscriber[F, V]] = {
    def accumulate(
      stream: Stream[F, CommittableConsumerRecord[F, Option[V], Option[V]]],
      offsets: Map[TopicPartition, Long]
    ): Stream[F, CommittableConsumerRecord[F, Option[V], Option[V]]] = {
      def go(
        seenOffsets: Map[TopicPartition, Long],
        state: Chain[Chunk[CommittableConsumerRecord[F, Option[V], Option[V]]]],
        upToDate: Boolean,
      )(
        stream: Stream[F, CommittableConsumerRecord[F, Option[V], Option[V]]]
      ): Pull[F, Chunk[CommittableConsumerRecord[F, Option[V], Option[V]]], Unit] = {
        def chunkOffsets(
          recordChunk: Chunk[CommittableConsumerRecord[F, Option[V], Option[V]]]
        ): (Map[TopicPartition, Long], Boolean) =
          recordChunk.foldLeft((Map.empty[TopicPartition, Long], false)) {
            case ((acc, done), record) =>
              if (done) { (acc, done) } else {
                val state = acc ++ record.offset.offsets.mapValues(_.offset())
                if (state === seenOffsets) return (state, true)
                else (state, false)
              }
          }

        stream.pull.uncons.flatMap {
          case Some((recordChunk, next)) =>
            if (upToDate) {
              Pull.output1(recordChunk) >> go(seenOffsets, state, upToDate)(next)
            } else {
              val (newSeenOffsets, done) = chunkOffsets(recordChunk)

              if (done) Pull.output(Chunk.chain(state.append(recordChunk))) >> go(Map.empty, Chain.empty, done)(next)
              else go(newSeenOffsets, state.append(recordChunk), done)(next)
            }
          case None => Pull.output(Chunk.chain(state)) >> Pull.done
        }
      }

      go(Map.empty, Chain.empty, upToDate = false)(stream).stream.flatMap(Stream.chunk)
    }

    for {
      consumer <- consumerResource(settings)
      _ <- Resource.liftF(consumer.subscribeTo(topic.value))
      assignments <- Resource.liftF(consumer.assignment)
      offsets <- Resource.liftF(consumer.endOffsets(assignments))
    } yield
      new EventSubscriber[F, V] {
        override def subscribeEvent: Stream[F, Event[V]] =
          accumulate(consumer.stream, offsets).map { r =>
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
    settings: ConsumerSettings[F, Option[V], Option[V]]
  ): EventSubscriber[F, V] =
    new EventSubscriber[F, V] {
      override def subscribeEvent: Stream[F, Event[V]] =
        consumerStream(settings)
          .evalTap(
            x =>
              x.metrics.flatMap(
                m =>
                  Sync[F]
                    .delay(println(m.toList.map { case (name, value) => (name, value.metricValue()) }.mkString("\n")))
            )
          )
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
