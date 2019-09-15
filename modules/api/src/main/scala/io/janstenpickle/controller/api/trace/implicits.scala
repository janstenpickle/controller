package io.janstenpickle.controller.api.trace

import cats.data.{Kleisli, OptionT}
import cats.effect.Bracket
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Applicative}
import io.janstenpickle.controller.api.trace.Http4sUtils._
import natchez.{EntryPoint, Kernel, Span, TraceValue}
import org.http4s.{HttpRoutes, Request, Response}

object implicits {

  // Given an entry point and HTTP Routes in Kleisli[F, Span[F], *] return routes in F. A new span
  // is created with the URI path as the name, either as a continuation of the incoming trace, if
  // any, or as a new root. This can likely be simplified, I just did what the types were saying
  // and it works so :shrug:
  private def liftT[F[_]: Bracket[*[_], Throwable]](
    entryPoint: EntryPoint[F]
  )(routes: HttpRoutes[Kleisli[F, Span[F], *]]): HttpRoutes[F] =
    Kleisli[OptionT[F, *], Request[F], Response[F]] { req =>
      type G[A] = Kleisli[F, Span[F], A]
      val lift = λ[F ~> G](fa => Kleisli(_ => fa))
      val kernel = Kernel(req.headers.toList.map(h => h.name.value -> h.value).toMap)
      val spanR = entryPoint.continueOrElseRoot(req.uri.path, kernel)
      OptionT[F, Response[F]] {
        spanR.use { span =>
          val lower = λ[G ~> F](_(span))
          span.put(requestFields(req): _*) *> routes
            .run(req.mapK(lift))
            .mapK(lower)
            .map(_.mapK(lower))
            .value
            .flatMap {
              case Some(resp) =>
                span
                  .put(responseFields(resp): _*)
                  .as(Some(resp))
              case None => Applicative[F].pure(None)
            }
        }
      }
    }

  implicit class EntryPointOps[F[_]](self: EntryPoint[F]) {
    def liftT(routes: HttpRoutes[Kleisli[F, Span[F], *]])(implicit ev: Bracket[F, Throwable]): HttpRoutes[F] =
      implicits.liftT(self)(routes)
  }

}
