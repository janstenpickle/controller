package io.janstenpickle.controller.api.endpoint

import cats.Semigroupal
import cats.data.ValidatedNel
import cats.effect.Sync
import cats.mtl.ApplicativeHandle
import cats.syntax.apply._
import cats.syntax.either._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.remotecontrol.RemoteControls
import natchez.{Trace, TraceValue}
import org.http4s.{HttpRoutes, Response}

class RemoteApi[F[_]: Sync](remotes: RemoteControls[F])(
  implicit trace: Trace[F],
  ah: ApplicativeHandle[F, ControlError]
) extends Common[F] {
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

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / "send" / name / device / command =>
      refineOrBadReq(name, device, command) { (n, d, c) =>
        trace.span("remoteSendCommand") {
          trace.put(
            "name" -> TraceValue.stringToTraceValue(name),
            "device" -> TraceValue.stringToTraceValue(device),
            "command" -> TraceValue.stringToTraceValue(command)
          ) *>
            Ok(remotes.send(n, d, c))
        }
      }
    case POST -> Root / "learn" / name / device / command =>
      refineOrBadReq(name, device, command) { (n, d, c) =>
        trace.span("remoteLearnCommand") {
          trace.put(
            "name" -> TraceValue.stringToTraceValue(name),
            "device" -> TraceValue.stringToTraceValue(device),
            "command" -> TraceValue.stringToTraceValue(command)
          ) *>
            Ok(remotes.learn(n, d, c))
        }
      }
    case GET -> Root => trace.span("remoteListCommands") { Ok(remotes.listCommands) }
  }
}
