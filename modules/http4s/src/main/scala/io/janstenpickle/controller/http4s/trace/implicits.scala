package io.janstenpickle.controller.http4s.trace

import cats.data.{Kleisli, OptionT}
import cats.effect.{Bracket, Sync}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, Applicative}
import io.janstenpickle.controller.http4s.trace.Http4sUtils._
import io.janstenpickle.trace4cats.{Span, ToHeaders}
import io.janstenpickle.trace4cats.inject.{EntryPoint, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue.BooleanValue
import io.janstenpickle.trace4cats.model.SpanKind
import org.http4s.client.Client
import org.http4s.{HttpApp, HttpRoutes, Request, Response}

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
      val headers = req.headers.toList.map(h => h.name.value -> h.value).toMap
      val spanR = entryPoint.continueOrElseRoot(req.uri.path, SpanKind.Server, headers)
      OptionT[F, Response[F]] {
        spanR.use { span =>
          val lower = λ[G ~> F](_(span))
          span.putAll(requestFields(req): _*) *> routes
            .run(req.mapK(lift))
            .mapK(lower)
            .map(_.mapK(lower))
            .value
            .flatMap {
              case Some(resp) =>
                span
                  .putAll(("stats" -> BooleanValue(false)) :: responseFields(resp): _*)
                  .as(Some(resp))
              case None => Applicative[F].pure(None)
            }
        }
      }
    }

  implicit class EntryPointOps[F[_]](self: EntryPoint[F]) {
    def liftT(routes: HttpRoutes[Kleisli[F, Span[F], *]])(implicit ev: Bracket[F, Throwable]): HttpRoutes[F] =
      implicits.liftT(self)(routes)

    def lowerT(client: Client[F])(implicit ev: Sync[F]): Client[Kleisli[F, Span[F], *]] =
      implicits.lowerT[F](self)(client)
  }

  private def lowerT[F[_]: Sync](entryPoint: EntryPoint[F])(client: Client[F]): Client[Kleisli[F, Span[F], *]] = {
    type G[A] = Kleisli[F, Span[F], A]
    val trace = Trace[G]
    val lift = λ[F ~> G](fa => Kleisli(_ => fa))
    val responseToTrace: Response[F] => Response[G] = resp => resp.mapK(lift)
    val traceToClientRequest: Request[G] => Request[F] =
      req => {
        val headers = req.headers.toList.map(h => h.name.value -> h.value).toMap
        val spanR = entryPoint.continueOrElseRoot(req.uri.path, SpanKind.Client, headers)
        val lower = λ[G ~> F](x => spanR.use(x.run))
        req.mapK(lower)

      }

    def contextHttpApp(app: HttpApp[F]): Kleisli[G, Request[G], Response[G]] =
      Kleisli[G, Request[G], Response[G]] { request =>
        trace.headers(ToHeaders.w3c).flatMap { headers =>
          val req = request.putHeaders(Http4sUtils.traceHeadersToHttp(headers): _*)

          app
            .mapK(lift)
            .map(responseToTrace)
            .flatMapF { resp =>
              trace
                .putAll(
                  "http.status_code" -> resp.status.code,
                  "http.status_message" -> resp.status.reason,
                  "stats" -> false
                )
                .map(_ => resp)
            }
            .run(traceToClientRequest(req))
        }
      }

    Client.fromHttpApp[G](contextHttpApp(client.toHttpApp))
  }

}
