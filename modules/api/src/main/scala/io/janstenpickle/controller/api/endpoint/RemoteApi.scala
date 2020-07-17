package io.janstenpickle.controller.api.endpoint

import cats.Semigroupal
import cats.data.ValidatedNel
import cats.effect.Sync
import cats.mtl.ApplicativeHandle
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.functor._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.refined._
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model.RemoteCommandSource
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.remotecontrol.RemoteControls.RemoteControlDef
import io.janstenpickle.trace4cats.inject.Trace
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.{HttpRoutes, Response}

class RemoteApi[F[_]: Sync](remotes: RemoteControls[F])(
  implicit trace: Trace[F],
  ah: ApplicativeHandle[F, ControlError]
) extends Common[F] {
  import RemoteApi._

  def refineOrBadReq(name: String, device: String, command: String)(
    f: (NonEmptyString, NonEmptyString, NonEmptyString) => F[Response[F]]
  ): F[Response[F]] =
    Semigroupal
      .map3[ValidatedNel[String, *], NonEmptyString, NonEmptyString, NonEmptyString, F[Response[F]]](
        refineV[NonEmpty](name).toValidatedNel,
        refineV[NonEmpty](device).toValidatedNel,
        refineV[NonEmpty](command).toValidatedNel
      )(f)
      .leftMap(errs => BadRequest(errs.toList.mkString(",")))
      .merge

  def refineOrBadReq(name: String, sourceName: String, sourceType: String, device: String, command: String)(
    f: (NonEmptyString, RemoteCommandSource, NonEmptyString, NonEmptyString) => F[Response[F]]
  ): F[Response[F]] =
    Semigroupal
      .map5[ValidatedNel[String, *], NonEmptyString, NonEmptyString, NonEmptyString, NonEmptyString, NonEmptyString, F[
        Response[F]
      ]](
        refineV[NonEmpty](name).toValidatedNel,
        refineV[NonEmpty](sourceName).toValidatedNel,
        refineV[NonEmpty](sourceType).toValidatedNel,
        refineV[NonEmpty](device).toValidatedNel,
        refineV[NonEmpty](command).toValidatedNel
      )((n, sn, st, d, c) => f(n, RemoteCommandSource(sn, st), d, c))
      .leftMap(errs => BadRequest(errs.toList.mkString(",")))
      .merge

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / "send" / name / device / command =>
      refineOrBadReq(name, device, command) { (n, d, c) =>
        trace.span("api.remote.send.command") {
          trace.putAll("name" -> name, "device" -> device, "command" -> command) *>
            Ok(remotes.send(n, None, d, c))
        }
      }
    case POST -> Root / "send" / name / sourceName / sourceType / device / command =>
      refineOrBadReq(name, sourceName, sourceType, device, command) { (n, cs, d, c) =>
        trace.span("api.remote.send.command") {
          trace.putAll(
            "name" -> name,
            "device" -> device,
            "command" -> command,
            "command_source" -> sourceName,
            "command_source_type" -> sourceType
          ) *>
            Ok(remotes.send(n, Some(cs), d, c))
        }
      }
    case POST -> Root / "learn" / name / device / command =>
      refineOrBadReq(name, device, command) { (n, d, c) =>
        trace.span("api.remote.learn.command") {
          trace.putAll("name" -> name, "device" -> device, "command" -> command) *>
            Ok(remotes.learn(n, d, c))
        }
      }

    case GET -> Root / "commands" => trace.span("api.remote.list.commands") { Ok(remotes.listCommands) }

    case GET -> Root :? IncludeAllRemotesParamMatcher(all) =>
      trace.span("api.remote.list.remotes") {
        Ok(remotes.listRemotes.map { remotes =>
          if (all.getOrElse(false)) remotes.map(_.name)
          else remotes.collect { case RemoteControlDef(name, supportsLearning) if supportsLearning => name }
        })
      }

  }
}

object RemoteApi {
  object IncludeAllRemotesParamMatcher extends OptionalQueryParamDecoderMatcher[Boolean]("all")
}
