package io.janstenpickle.controller.events

import cats.effect.Clock
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{~>, Monad}
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.SpanKind

trait EventPublisher[F[_], A] { outer =>
  def publish1(a: A): F[Unit]
  def publish1Event(a: Event[A]): F[Unit]
  def pipe: Pipe[F, A, Unit]
  def eventPipe: Pipe[F, Event[A], Unit]
  def narrow[B <: A]: EventPublisher[F, B] = contramap[B](x => x)
  def contramap[B](f: B => A): EventPublisher[F, B] = new EventPublisher[F, B] {
    override def publish1(a: B): F[Unit] = outer.publish1(f(a))
    override def pipe: Pipe[F, B, Unit] = _.map(f).through(outer.pipe)
    override def publish1Event(a: Event[B]): F[Unit] = outer.publish1Event(a.map(f))
    override def eventPipe: Pipe[F, Event[B], Unit] = _.map(_.map(f)).through(outer.eventPipe)

    override def updateSource(source: String): EventPublisher[F, B] = outer.updateSource(source).contramap(f)
  }
  def mapK[G[_]](fk: F ~> G, gk: G ~> F): EventPublisher[G, A] = EventPublisher.mapK(fk, gk)(this)
  def updateSource(source: String): EventPublisher[F, A]
}

object EventPublisher {
  def fromTopic[F[_]: Monad, A](
    topic: Topic[F, Option[Event[A]]],
    source: String
  )(implicit clock: Clock[F], trace: Trace[F]): EventPublisher[F, A] = {
    def pub(src: String): EventPublisher[F, A] = new EventPublisher[F, A] {
      override def publish1(a: A): F[Unit] = Trace[F].span("publish1", SpanKind.Producer) {
        trace.headers.flatMap { headers =>
          Event(a, src, headers).flatMap(evt => topic.publish1(Some(evt)))
        }
      }

      override def pipe: Pipe[F, A, Unit] =
        _.chunks
          .flatMap { as =>
            Stream.evals(
              trace.headers
                .flatMap { headers =>
                  as.traverse(Event(_, src, headers).map(Some(_)))
                }
            )
          }
          .through(topic.publish)

      override def publish1Event(a: Event[A]): F[Unit] = Trace[F].span("publish1Event", SpanKind.Producer) {
        trace.headers.flatMap { headers =>
          topic.publish1(Some(a.copy(headers = headers ++ a.headers)))
        }
      }

      override def eventPipe: Pipe[F, Event[A], Unit] = _.map(Some(_)).through(topic.publish)

      override def updateSource(source: String): EventPublisher[F, A] = pub(source)
    }

    pub(source)
  }

  private def mapK[F[_], G[_], A](fk: F ~> G, gk: G ~> F)(publisher: EventPublisher[F, A]): EventPublisher[G, A] =
    new EventPublisher[G, A] {
      override def publish1(a: A): G[Unit] = fk(publisher.publish1(a))

      override def pipe: Pipe[G, A, Unit] = st => publisher.pipe(st.translate(gk)).translate(fk)

      override def publish1Event(a: Event[A]): G[Unit] = fk(publisher.publish1Event(a))

      override def eventPipe: Pipe[G, Event[A], Unit] = st => publisher.eventPipe(st.translate(gk)).translate(fk)

      override def updateSource(source: String): EventPublisher[G, A] =
        EventPublisher.mapK(fk, gk)(publisher.updateSource(source))
    }
}
