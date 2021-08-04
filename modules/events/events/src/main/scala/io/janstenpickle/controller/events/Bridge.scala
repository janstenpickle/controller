package io.janstenpickle.controller.events

import cats.Parallel
import cats.effect.kernel.{Outcome, Spawn}
import cats.effect.syntax.spawn._
import cats.effect.{Concurrent, Resource}
import cats.syntax.flatMap._

object Bridge {
  def apply[F[_]: Concurrent: Spawn: Parallel](
    source: Events[F],
    sink: Events[F]
  ): Resource[F, F[Outcome[F, Throwable, Unit]]] = {
    def connect[A](src: EventPubSub[F, A], snk: EventPubSub[F, A]) =
      src.subscriberResource.flatMap(_.subscribe.through(snk.publisher.pipe).compile.drain.background) >>
        snk.subscriberResource.flatMap(_.subscribe.through(src.publisher.pipe).compile.drain.background)

    Parallel.parMap7(
      connect(source.remote, sink.remote),
      connect(source.switch, sink.switch),
      connect(source.config, sink.config),
      connect(source.discovery, sink.discovery),
      connect(source.activity, sink.activity),
      connect(source.`macro`, sink.`macro`),
      connect(source.command, sink.command)
    )((r, s, c, d, a, m, co) => r >> s >> c >> d >> a >> m >> co)
  }
}
