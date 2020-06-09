package io.janstenpickle.controller.configsource.extruder

import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.Diff
import io.janstenpickle.controller.events.EventPublisher

import fs2.Stream

object Events {
  def fromDiff[F[_], A, K, V](
    publisher: EventPublisher[F, A],
    addedEvent: (K, V) => A,
    removedEvent: (K, V) => A,
    updatedEvent: (K, V) => A
  )(diff: Diff[K, V])(implicit compiler: Stream.Compiler[F, F]): F[Unit] =
    Stream
      .emits[F, A](
        diff.added.map(addedEvent.tupled) ++ diff.removed.map(removedEvent.tupled) ++ diff.updated
          .map(updatedEvent.tupled)
      )
      .through(publisher.pipe)
      .compile
      .drain

  def fromDiff[F[_], A, K, V](publisher: EventPublisher[F, A], addedEvent: (K, V) => A, removedEvent: (K, V) => A)(
    diff: Diff[K, V]
  )(implicit compiler: Stream.Compiler[F, F]): F[Unit] =
    fromDiff(publisher, addedEvent, removedEvent, addedEvent)(diff)

  def fromDiffValues[F[_], A, K, V](
    publisher: EventPublisher[F, A],
    addedEvent: V => A,
    removedEvent: V => A,
    updatedEvent: V => A
  )(diff: Diff[K, V])(implicit compiler: Stream.Compiler[F, F]): F[Unit] =
    fromDiff[F, A, K, V](
      publisher,
      (_: K, v: V) => addedEvent(v),
      (_: K, v: V) => removedEvent(v),
      (_: K, v: V) => updatedEvent(v)
    )(diff)

  def fromDiffValues[F[_], A, K, V](publisher: EventPublisher[F, A], addedEvent: V => A, removedEvent: V => A)(
    diff: Diff[K, V]
  )(implicit compiler: Stream.Compiler[F, F]): F[Unit] =
    fromDiffValues(publisher, addedEvent, removedEvent, addedEvent)(diff)
}
