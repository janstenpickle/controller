package io.janstenpickle.controller.http4s.client

import cats.data.{EitherT, Kleisli}
import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Applicative, ApplicativeError}
import org.http4s.client.Client
import org.http4s.{Request, Response}

object EitherTClient {
  def apply[F[_]: Sync, E <: Throwable](client: Client[F]): Client[EitherT[F, E, *]] = {
    type G[A] = EitherT[F, E, A]
    val lift = λ[F ~> G](EitherT.liftF(_))
    val lower =
      λ[G ~> F](ga => ga.value.flatMap(_.fold(ApplicativeError[F, Throwable].raiseError, Applicative[F].pure)))

    Client.fromHttpApp(Kleisli[G, Request[G], Response[G]] { req =>
      lift(client.toHttpApp.run(req.mapK(lower)).map(_.mapK(lift)))
    })
  }
}
