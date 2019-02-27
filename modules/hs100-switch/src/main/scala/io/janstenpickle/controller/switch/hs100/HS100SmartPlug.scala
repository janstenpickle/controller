package io.janstenpickle.controller.switch.hs100

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
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.control.switch.polling.{PollingSwitch, PollingSwitchErrors}
import io.janstenpickle.controller.switch.hs100.Encryption._
import io.janstenpickle.controller.switch.{State, Switch}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class HS100SmartPlug[F[_]](config: HS100SmartPlug.Config, ec: ExecutionContext)(
  implicit F: Sync[F],
  cs: ContextShift[F],
  errors: HS100Errors[F]
) extends Switch[F] {
  import HS100SmartPlug._

  private def eval[A](fa: F[A]): F[A] = evalOn(fa, ec)

  def getInfo: F[Json] = eval(sendCommand(InfoCommand))

  override def name: NonEmptyString = config.name

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
    eval(sendCommand(SwitchOnCommand)).flatMap(parseSetResponse)

  override def switchOff: F[Unit] =
    eval(sendCommand(SwitchOffCommand)).flatMap(parseSetResponse)

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
      suspendErrors {
        val socket = new Socket(config.host.value, config.port.value)
        val inputStream = socket.getInputStream
        val outputStream = socket.getOutputStream
        (socket, inputStream, outputStream)
      }
    } {
      case (s, is, os) =>
        suspendErrors {
          os.close()
          is.close()
          s.close()
        }
    }

    resource.use {
      case (_, inputStream, outputStream) =>
        for {
          _ <- suspendErrors(outputStream.write(encryptWithHeader(command)))
          response <- decrypt(inputStream)
          result <- parser.parse(response).fold[F[Json]](errors.decodingFailure(name, _), _.pure[F])
        } yield result
    }
  }
}

object HS100SmartPlug {
  private val System = "system"
  private val SetRelayState = "set_relay_state"
  private val GetSysInfo = "get_sysinfo"

  private val InfoCommand = s"""{"$System":{"$GetSysInfo":null}}"""
  private val SwitchOnCommand = s"""{"$System":{"$SetRelayState":{"state":1}}}}"""
  private val SwitchOffCommand = s"""{"$System":{"$SetRelayState":{"state":0}}}}"""

  implicit val stateDecoder: Decoder[State] = Decoder.decodeInt.map {
    case 1 => State.On
    case _ => State.Off
  }

  case class Config(name: NonEmptyString, host: NonEmptyString, port: PortNumber = refineMV(9999))
  case class PollingConfig(pollInterval: FiniteDuration = 2.seconds, errorThreshold: PosInt = PosInt(2))

  def apply[F[_]: Sync: ContextShift: HS100Errors](config: Config, ec: ExecutionContext): HS100SmartPlug[F] =
    new HS100SmartPlug[F](config, ec)

  def apply[F[_]: Sync: ContextShift: HS100Errors](config: Config): Resource[F, HS100SmartPlug[F]] =
    cachedExecutorResource.map(apply[F](config, _))

  private def poller[F[_]: Concurrent: Timer: PollingSwitchErrors](
    config: PollingConfig,
    switch: HS100SmartPlug[F]
  ): Resource[F, Switch[F]] =
    PollingSwitch[F](switch, config.pollInterval, config.errorThreshold)

  def polling[F[_]: Concurrent: ContextShift: Timer: HS100Errors: PollingSwitchErrors](
    config: Config,
    pollingConfig: PollingConfig,
    ec: ExecutionContext
  ): Resource[F, Switch[F]] = poller(pollingConfig, apply[F](config, ec))

  def polling[F[_]: Concurrent: ContextShift: Timer: HS100Errors: PollingSwitchErrors](
    config: Config,
    pollingConfig: PollingConfig
  ): Resource[F, Switch[F]] = apply[F](config).flatMap(poller(pollingConfig, _))

}
