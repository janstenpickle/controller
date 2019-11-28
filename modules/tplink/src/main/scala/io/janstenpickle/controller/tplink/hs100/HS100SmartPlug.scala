package io.janstenpickle.controller.tplink.hs100

import java.io.{InputStream, OutputStream}
import java.net.Socket

import cats.effect._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{parser, Decoder, Json}
import io.janstenpickle.control.switch.polling.{PollingSwitch, PollingSwitchErrors}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.Switch
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.tplink.hs100.Encryption._
import natchez.Trace

import scala.concurrent.duration._

class HS100SmartPlug[F[_]](config: HS100SmartPlug.Config, blocker: Blocker)(
  implicit F: Sync[F],
  cs: ContextShift[F],
  errors: HS100Errors[F],
  trace: Trace[F]
) extends Switch[F] {
  import HS100SmartPlug._
  def getInfo: F[Json] = blocker.blockOn(sendCommand(InfoCommand))

  override def name: NonEmptyString = config.name
  override val device: NonEmptyString = NonEmptyString("HS100")

  override def getState: F[State] =
    getInfo.flatMap { json =>
      val cursor = json.hcursor
        .downField(System)
        .downField(GetSysInfo)
        .downField("relay_state")

      cursor.focus.fold[F[State]](errors.missingJson(name, cursor.history))(
        _.as[State].fold(errors.decodingFailure(name, _), _.pure[F])
      )
    }

  override def switchOn: F[Unit] =
    blocker.blockOn(sendCommand(SwitchOnCommand)).flatMap(parseSetResponse)

  override def switchOff: F[Unit] =
    blocker.blockOn(sendCommand(SwitchOffCommand)).flatMap(parseSetResponse)

  private def parseSetResponse(json: Json): F[Unit] = {
    val cursor = json.hcursor.downField(System).downField(SetRelayState).downField("err_code")

    cursor.focus
      .fold[F[Unit]](errors.missingJson(name, cursor.history))(_.as[Int] match {
        case Right(0) => ().pure[F]
        case Right(err) => errors.command(name, err)
        case Left(err) => errors.decodingFailure(name, err)
      })
  }

  private def sendCommand(command: String): F[Json] = {
    val resource: Resource[F, (Socket, InputStream, OutputStream)] = Resource.make {
      F.delay {
        val socket = new Socket(config.host.value, config.port.value)
        val inputStream = socket.getInputStream
        val outputStream = socket.getOutputStream
        (socket, inputStream, outputStream)
      }
    } {
      case (s, is, os) =>
        F.delay {
          os.close()
          is.close()
          s.close()
        }
    }

    resource.use {
      case (_, inputStream, outputStream) =>
        for {
          _ <- F.delay(outputStream.write(encryptWithHeader(command)))
          response <- decrypt(inputStream)
          result <- parser.parse(response).fold[F[Json]](errors.decodingFailure(name, _), _.pure[F])
        } yield result
    }
  }

}

object HS100SmartPlug {
  private final val System = "system"
  private final val SetRelayState = "set_relay_state"
  private final val GetSysInfo = "get_sysinfo"

  private final val InfoCommand = s"""{"$System":{"$GetSysInfo":null}}"""
  private final val SwitchOnCommand = s"""{"$System":{"$SetRelayState":{"state":1}}}}"""
  private final val SwitchOffCommand = s"""{"$System":{"$SetRelayState":{"state":0}}}}"""

  implicit final val stateDecoder: Decoder[State] = Decoder.decodeInt.map {
    case 1 => State.On
    case _ => State.Off
  }

  case class Config(name: NonEmptyString, host: NonEmptyString, port: PortNumber = refineMV(9999))
  case class PollingConfig(pollInterval: FiniteDuration = 2.seconds, errorThreshold: PosInt = PosInt(2))

  def apply[F[_]: Sync: ContextShift: HS100Errors: Trace](config: Config, blocker: Blocker): Switch[F] =
    TracedSwitch(new HS100SmartPlug[F](config, blocker), "manufacturer" -> "tplink")

  private def poller[F[_]: Sync: PollingSwitchErrors: Trace, G[_]: Concurrent: Timer](
    config: PollingConfig,
    switch: Switch[F],
    onUpdate: State => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Switch[F]] =
    PollingSwitch[F, G](switch, config.pollInterval, config.errorThreshold, onUpdate)

  def polling[F[_]: Sync: ContextShift: HS100Errors: PollingSwitchErrors: Trace, G[_]: Concurrent: Timer](
    config: Config,
    pollingConfig: PollingConfig,
    onUpdate: State => F[Unit],
    blocker: Blocker
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Switch[F]] =
    poller[F, G](pollingConfig, apply[F](config, blocker), onUpdate)

}
