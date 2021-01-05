package io.janstenpickle.controller.events.websocket

import cats.ApplicativeError
import cats.data.Kleisli
import cats.effect.concurrent.Deferred
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Timer}
import cats.instances.list._
import cats.syntax.flatMap._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import io.janstenpickle.controller.events.{Event, EventPublisher, EventSubscriber}
import io.janstenpickle.controller.websocket.client.JavaWebSocketClient
import io.janstenpickle.trace4cats.base.context.Provide

import java.net.URI
import scala.concurrent.duration._

object JavaWebsocket {

  def receive[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, A: Decoder, Ctx](
    host: NonEmptyString,
    port: PortNumber,
    path: String,
    blocker: Blocker,
    publisher: EventPublisher[F, A],
    k: Kleisli[Resource[G, *], String, Ctx],
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Unit]] = {
    val uri = new URI(s"ws://$host:$port/events/$path/subscribe")

    JavaWebSocketClient.receiveString[F, G, Ctx](
      uri,
      blocker,
      k,
      str =>
        ApplicativeError[F, Throwable].fromEither(parse(str).flatMap(_.as[Event[A]])).flatMap(publisher.publish1Event)
    )
  }

  def send[F[_]: Concurrent: Timer: ContextShift, G[_]: ConcurrentEffect, A: Encoder, Ctx](
    host: NonEmptyString,
    port: PortNumber,
    path: String,
    blocker: Blocker,
    subscriber: EventSubscriber[F, A],
    k: Kleisli[Resource[G, *], String, Ctx],
    state: Option[Deferred[F, F[List[Event[A]]]]] = None
  )(implicit provide: Provide[G, F, Ctx]): Resource[F, F[Unit]] = {
    val uri = new URI(s"ws://$host:$port/events/$path/publish")

    val stream =
      state
        .fold(subscriber.subscribeEvent)(state => Stream.evals(state.get.flatten) ++ subscriber.subscribeEvent)

    val periodic = state.fold[Stream[F, Event[A]]](Stream.empty)(
      state => Stream.awakeEvery[F](10.minutes).flatMap(_ => Stream.evals(state.get.flatten))
    )

    JavaWebSocketClient.sendString[F, G, Ctx](uri, blocker, stream.merge(periodic).map(_.asJson.noSpacesSortKeys), k)
  }
}
