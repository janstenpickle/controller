package io.janstenpickle.controller.tplink.hs100

import cats.effect._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import eu.timepit.refined.refineMV
import cats.{Eq, Monad}
import cats.effect.concurrent.Ref
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, Json}
import io.janstenpickle.control.switch.polling.{PollingSwitch, PollingSwitchErrors}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.Switch
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.tplink.Constants._
import io.janstenpickle.controller.tplink.TplinkDevice
import natchez.Trace
import cats.syntax.apply._
import cats.syntax.functor._
import cats.instances.string._

import scala.concurrent.duration._

//class HS100SmartPlug[F[_]](tplink: TplinkDevice[F], state: Ref[F, State])(
//  implicit F: Monad[F],
//  errors: HS100Errors[F],
//) extends Switch[F] {
//  import HS100SmartPlug._
//  def getInfo: F[Json] = tplink.sendCommand(s"{$InfoCommand}")
//
//  override def name: NonEmptyString = tplink.deviceName
//  override val device: NonEmptyString = NonEmptyString("HS100")
//
//  override def getState: F[State] =
//    getInfo.flatMap { json =>
//      val cursor = json.hcursor
//        .downField(System)
//        .downField(GetSysInfo)
//        .downField("relay_state")
//
//      cursor.focus.fold[F[State]](errors.missingJson(name, cursor.history))(
//        _.as[State].fold(errors.decodingFailure(name, _), _.pure[F])
//      )
//    }
//
//  override def switchOn: F[Unit] =
//    tplink.sendCommand(SwitchOnCommand).flatMap(parseSetResponse) *> state.set(State.On)
//
//  override def switchOff: F[Unit] =
//    tplink.sendCommand(SwitchOffCommand).flatMap(parseSetResponse) *> state.set(State.Off)
//
//  private def parseSetResponse(json: Json): F[Unit] = {
//    val cursor = json.hcursor.downField(System).downField(SetRelayState).downField("err_code")
//
//    cursor.focus
//      .fold[F[Unit]](errors.missingJson(name, cursor.history))(_.as[Int] match {
//        case Right(0) => ().pure[F]
//        case Right(err) => errors.command(name, err)
//        case Left(err) => errors.decodingFailure(name, err)
//      })
//  }
//
//}

trait HS100SmartPlug[F[_]] extends Switch[F] {
  def refresh: F[Unit]
}

object HS100SmartPlug {
  private final val SetRelayState = "set_relay_state"

  private final val SwitchOnCommand = s"""{"$System":{"$SetRelayState":{"state":1}}}}"""
  private final val SwitchOffCommand = s"""{"$System":{"$SetRelayState":{"state":0}}}}"""

  implicit final val stateDecoder: Decoder[State] = Decoder.decodeInt.map {
    case 1 => State.On
    case _ => State.Off
  }

  case class Config(
    name: NonEmptyString,
    host: NonEmptyString,
    port: PortNumber = refineMV(9999),
    timeout: FiniteDuration = 5.seconds
  )
  case class PollingConfig(pollInterval: FiniteDuration = 2.seconds, errorThreshold: PosInt = PosInt(2))

//  def apply[F[_]: Concurrent: Timer: ContextShift: HS100Errors: Trace](config: Config, blocker: Blocker): Switch[F] =
//    TracedSwitch(
//      new HS100SmartPlug[F](TplinkDevice[F](config.name, config.host, config.port, config.timeout, blocker)),
//      "manufacturer" -> "tplink"
//    )
//
//  private def poller[F[_]: Sync: PollingSwitchErrors: Trace, G[_]: Concurrent: Timer](
//    config: PollingConfig,
//    switch: Switch[F],
//    onUpdate: State => F[Unit]
//  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Switch[F]] =
//    PollingSwitch[F, G](switch, config.pollInterval, config.errorThreshold, onUpdate)
//
//  def polling[F[_]: Concurrent: Timer: ContextShift: HS100Errors: PollingSwitchErrors: Trace, G[_]: Concurrent: Timer](
//    config: Config,
//    pollingConfig: PollingConfig,
//    onUpdate: State => F[Unit],
//    blocker: Blocker
//  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Switch[F]] =
//    poller[F, G](pollingConfig, apply[F](config, blocker), onUpdate)

  implicit def eq[F[_]]: Eq[HS100SmartPlug[F]] = Eq.by(_.name.value)

  def apply[F[_]: Sync](tplink: TplinkDevice[F])(implicit errors: HS100Errors[F]): F[HS100SmartPlug[F]] = {
    def getInfo: F[Json] = tplink.sendCommand(s"{$InfoCommand}")

    def _getState: F[State] =
      getInfo.flatMap { json =>
        val cursor = json.hcursor
          .downField(System)
          .downField(GetSysInfo)
          .downField("relay_state")

        cursor.focus.fold[F[State]](errors.missingJson(tplink.deviceName, cursor.history))(
          _.as[State].fold(errors.decodingFailure(tplink.deviceName, _), _.pure[F])
        )
      }

    def parseSetResponse(json: Json): F[Unit] = {
      val cursor = json.hcursor.downField(System).downField(SetRelayState).downField("err_code")

      cursor.focus
        .fold[F[Unit]](errors.missingJson(tplink.deviceName, cursor.history))(_.as[Int] match {
          case Right(0) => ().pure[F]
          case Right(err) => errors.command(tplink.deviceName, err)
          case Left(err) => errors.decodingFailure(tplink.deviceName, err)
        })
    }

    for {
      initialState <- _getState
      state <- Ref.of(initialState)
    } yield
      new HS100SmartPlug[F] {
        override def refresh: F[Unit] = getState.flatMap(state.set)
        override def name: NonEmptyString = tplink.deviceName
        override val device: NonEmptyString = NonEmptyString("HS100")
        override def getState: F[State] = state.get
        override def switchOn: F[Unit] =
          tplink.sendCommand(SwitchOnCommand).flatMap(parseSetResponse) *> state.set(State.On)
        override def switchOff: F[Unit] =
          tplink.sendCommand(SwitchOffCommand).flatMap(parseSetResponse) *> state.set(State.Off)
      }
  }
}
