package io.janstenpickle.controller.tplink.device

import java.time.{Instant, ZoneOffset}
import java.time.temporal.ChronoField
import java.util.concurrent.TimeUnit

import cats.effect._
import cats.effect.concurrent.Ref
import cats.instances.string._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Eq}
import eu.timepit.refined.refineMV
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{ACursor, Decoder, Json}
import io.janstenpickle.controller.model.{Room, State}
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Metadata, Switch, SwitchType}
import io.janstenpickle.controller.tplink.Constants._
import io.janstenpickle.controller.tplink.TplinkClient

import scala.concurrent.duration._

sealed trait TplinkDevice[F[_]] extends Switch[F] {
  def room: Option[Room]
  def refresh: F[Unit]
  def rename(name: NonEmptyString, room: Option[Room]): F[Unit]
  def roomName: NonEmptyString = room.fold(name)(r => NonEmptyString.unsafeFrom(s"${r.value}_${name.value}"))
}

object TplinkDevice {

  trait SmartPlug[F[_]] extends TplinkDevice[F]

  trait SmartBulb[F[_]] extends TplinkDevice[F] {
    def dimmable: Boolean
    def colour: Boolean
    def colourTemp: Boolean
    def brightnessUp: F[Unit]
    def brightnessDown: F[Unit]
    def saturationUp: F[Unit]
    def saturationDown: F[Unit]
    def hueUp: F[Unit]
    def hueDown: F[Unit]
    def tempUp: F[Unit]
    def tempDown: F[Unit]
  }

  implicit final val stateDecoder: Decoder[State] = Decoder.decodeInt.map {
    case 1 => State.On
    case _ => State.Off
  }

  private final val manufacturer = "TP Link"

  private final val SetRelayState = "set_relay_state"

  private final val PlugSwitchOnCommand = s"""{"$System":{"$SetRelayState":{"state":1}}}}"""
  private final val PlugSwitchOffCommand = s"""{"$System":{"$SetRelayState":{"state":0}}}}"""

  final val BulbCommandKey = "smartlife.iot.smartbulb.lightingservice"
  final val SetBulbState = "transition_light_state"
  final val GetBulbState = "get_light_state"
  final val BulbInfoCommand = s"""{"$BulbCommandKey":{"$GetBulbState":null}}"""
  final val BulbOnOff = "on_off"
  final val BulbBrightness = "brightness"
  final val BulbHue = "hue"
  final val BulbSaturation = "saturation"
  final val BulbTemp = "color_temp"
  final val BulbSwitchOnCommand = s"""{"$BulbCommandKey":{"$SetBulbState":{"$BulbOnOff":1}}}}"""
  final val BulbSwitchOffCommand = s"""{"$BulbCommandKey":{"$SetBulbState":{"$BulbOnOff":0}}}}"""

  private final val maxBrightness = 100
  private final val minBrightness = 0
  private final val brightnessStep = 10

  private final val maxTemp = 9000
  private final val minTemp = 2500
  private final val tempStep = 100

  private final val maxHue = 360
  private final val minHue = 0
  private final val hueStep = 15

  private final val maxSaturation = 100
  private final val minSaturation = 0
  private final val saturationStep = 10

  case class Config(
    name: NonEmptyString,
    host: NonEmptyString,
    port: PortNumber = refineMV(9999),
    timeout: FiniteDuration = 5.seconds
  )

  case class PollingConfig(pollInterval: FiniteDuration = 2.seconds, errorThreshold: PosInt = PosInt(2))

  implicit def eq[F[_]]: Eq[TplinkDevice[F]] = Eq.by { dev =>
    dev.room.fold(dev.name.value)(r => s"${dev.name}|$r")
  }

  def decodeOrError[F[_]: Applicative, A: Decoder](cursor: ACursor, deviceName: NonEmptyString)(
    implicit errors: TplinkDeviceErrors[F]
  ): F[A] =
    cursor.focus.fold[F[A]](errors.missingJson(deviceName, cursor.history))(
      _.as[A].fold(errors.decodingFailure(deviceName, _), _.pure[F])
    )

  def plug[F[_]: Sync](
    tplink: TplinkClient[F],
    model: NonEmptyString,
    id: String,
    discovered: Json,
    onUpdate: SwitchKey => F[Unit]
  )(implicit errors: TplinkDeviceErrors[F], timer: Timer[F]): F[SmartPlug[F]] = {
    val switchKey = SwitchKey(model, tplink.deviceName)

    def parseState(json: Json): F[State] = decodeOrError[F, State](
      cursor = json.hcursor
        .downField(System)
        .downField(GetSysInfo)
        .downField("relay_state"),
      tplink.deviceName
    )

    def setTime =
      for {
        millis <- timer.clock.realTime(TimeUnit.MILLISECONDS)
        time = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)
        timeCommand = s"""{"time":{"set_timezone":{"year":${time.getYear},"month":${time.getMonthValue},"mday":${time.getDayOfMonth},"hour":${time.getHour},"min":${time.getMinute},"sec":${time.getSecond},"index":0}}}"""
        _ <- tplink.sendCommand(timeCommand)
      } yield ()

    def _getState: F[State] = tplink.getInfo.flatMap(parseState)

    for {
      _ <- setTime
      initialState <- parseState(discovered).handleErrorWith(_ => _getState)
      state <- Ref.of(initialState)
    } yield
      new SmartPlug[F] {
        override def refresh: F[Unit] =
          for {
            current <- state.get
            newState <- _getState
            _ <- if (current != newState) state.set(newState) *> onUpdate(switchKey) else Applicative[F].unit
          } yield ()
        override def name: NonEmptyString = tplink.deviceName
        override def room: Option[Room] = tplink.deviceRoom
        override def device: NonEmptyString = model
        override def getState: F[State] = state.get
        override def switchOn: F[Unit] =
          tplink.sendCommand(PlugSwitchOnCommand).flatMap(tplink.parseSetResponse(System, SetRelayState)) *> state.set(
            State.On
          ) *> onUpdate(switchKey)
        override def switchOff: F[Unit] =
          tplink.sendCommand(PlugSwitchOffCommand).flatMap(tplink.parseSetResponse(System, SetRelayState)) *> state.set(
            State.Off
          ) *> onUpdate(switchKey)
        override def rename(name: NonEmptyString, room: Option[Room]): F[Unit] = tplink.rename(name, room)

        override def metadata: Metadata =
          Metadata(
            room = room.map(_.value),
            manufacturer = Some(manufacturer),
            model = Some(model.value),
            id = Some(id),
            `type` = SwitchType.Plug
          )
      }
  }

  case class BulbState(power: State, brightness: Int, hue: Int, saturation: Int, temp: Int)

  def bulb[F[_]](
    tplink: TplinkClient[F],
    model: NonEmptyString,
    id: String,
    discovered: Json,
    onUpdate: SwitchKey => F[Unit]
  )(implicit F: Sync[F], errors: TplinkDeviceErrors[F]): F[SmartBulb[F]] = {
    val switchKey = SwitchKey(model, tplink.deviceName)

    def getBulbInfo: F[Json] = tplink.sendCommand(BulbInfoCommand)

    def getSwitchState(info: Json): F[State] =
      decodeOrError[F, State](
        info.hcursor.downField(BulbCommandKey).downField(GetBulbState).downField(BulbOnOff),
        tplink.deviceName
      )

    def getAttribute(info: Json, name: String): F[Int] = {
      val cursor = info.hcursor
        .downField(BulbCommandKey)
        .downField(GetBulbState)
        .downField(name)

      cursor.focus
        .orElse(
          info.hcursor.downField(BulbCommandKey).downField(GetBulbState).downField("dft_on_state").downField(name).focus
        )
        .fold[F[Int]](errors.missingJson(tplink.deviceName, cursor.history))(
          _.as[Int].fold(errors.decodingFailure(tplink.deviceName, _), _.pure[F])
        )
    }

    def parseSate(j: Json): F[BulbState] =
      for {
        power <- getSwitchState(j)
        brightness <- getAttribute(j, BulbBrightness)
        hue <- getAttribute(j, BulbHue)
        saturation <- getAttribute(j, BulbSaturation)
        temp <- getAttribute(j, BulbTemp)
      } yield BulbState(power, brightness, hue, saturation, temp)

    val info = discovered.hcursor
      .downField(System)
      .downField(GetSysInfo)

    def _getState: F[BulbState] = getBulbInfo.flatMap(parseSate)

    for {
      initialState <- parseSate(discovered).handleErrorWith(_ => _getState)
      state <- Ref.of(initialState)
    } yield
      new SmartBulb[F] {
        override def refresh: F[Unit] =
          for {
            current <- state.get
            newState <- _getState
            _ <- if (current.power != newState.power) state.set(newState) *> onUpdate(switchKey)
            else state.set(newState)
          } yield ()

        override def name: NonEmptyString = tplink.deviceName

        override def room: Option[Room] = tplink.deviceRoom

        override def device: NonEmptyString = model

        override def getState: F[State] = state.get.map(_.power)

        override def switchOn: F[Unit] =
          (tplink
            .sendCommand(BulbSwitchOnCommand)
            .flatMap(tplink.parseSetResponse(BulbCommandKey, SetBulbState)) *> state.update(_.copy(power = State.On)))
            .onError {
              case th =>
                F.delay(th.printStackTrace())
            }
        override def switchOff: F[Unit] =
          (tplink
            .sendCommand(BulbSwitchOffCommand)
            .flatMap(tplink.parseSetResponse(BulbCommandKey, SetBulbState)) *> state.update(_.copy(power = State.Off)))
            .onError {
              case th =>
                F.delay(th.printStackTrace())
            }

        override def rename(name: NonEmptyString, room: Option[Room]): F[Unit] = tplink.rename(name, room)

        override val dimmable: Boolean = info.get[Int]("is_dimmable").toOption.fold(false)(_ == 1)
        override val colour: Boolean = info.get[Int]("is_color").toOption.fold(false)(_ == 1)
        override val colourTemp: Boolean = info.get[Int]("is_variable_color_temp").toOption.fold(false)(_ == 1)

        private def stepAttribute(
          cond: Boolean,
          attr: String,
          stateAttr: BulbState => Int,
          update: (BulbState, Int) => BulbState
        )(f: Int => Boolean, g: Int => Int): F[Unit] =
          if (cond)
            state.get.flatMap { s =>
              if (f(stateAttr(s))) {
                lazy val newAttr = g(stateAttr(s))

                tplink
                  .sendCommand(s"""{"$BulbCommandKey": {"$SetBulbState": {"$attr": $newAttr}}}""")
                  .flatMap(tplink.parseSetResponse(BulbCommandKey, SetBulbState)) *> state
                  .update(update(_, newAttr))
              } else {
                F.unit
              }
            } else F.unit

        private def stepDown(
          cond: Boolean,
          attr: String,
          stateAttr: BulbState => Int,
          update: (BulbState, Int) => BulbState,
          min: Int,
          step: Int
        ) =
          stepAttribute(cond, attr, stateAttr, update)(_ > min, { s =>
            val diff = s - step
            if (diff > step) s - step
            else s - diff
          })

        private def stepUp(
          cond: Boolean,
          attr: String,
          stateAttr: BulbState => Int,
          update: (BulbState, Int) => BulbState,
          max: Int,
          step: Int
        ) =
          stepAttribute(cond, attr, stateAttr, update)(_ < max, { s =>
            val diff = max - s
            if (diff > step) s + step
            else s + diff
          })

        override def brightnessUp: F[Unit] =
          stepUp(dimmable, "brightness", _.brightness, (s, v) => s.copy(brightness = v), maxBrightness, brightnessStep)
        override def brightnessDown: F[Unit] =
          stepDown(
            dimmable,
            "brightness",
            _.brightness,
            (s, v) => s.copy(brightness = v),
            minBrightness,
            brightnessStep
          )

        override def saturationUp: F[Unit] =
          stepUp(colour, "saturation", _.saturation, (s, v) => s.copy(saturation = v), maxSaturation, saturationStep)
        override def saturationDown: F[Unit] =
          stepDown(colour, "saturation", _.saturation, (s, v) => s.copy(saturation = v), minSaturation, saturationStep)

        override def hueUp: F[Unit] =
          stepUp(colour, "hue", _.hue, (s, v) => s.copy(hue = v), maxHue, hueStep)
        override def hueDown: F[Unit] =
          stepDown(colour, "hue", _.hue, (s, v) => s.copy(hue = v), minHue, hueStep)

        override def tempUp: F[Unit] =
          stepUp(colour, "temp", _.temp, (s, v) => s.copy(temp = v), maxTemp, tempStep)
        override def tempDown: F[Unit] =
          stepDown(colour, "temp", _.temp, (s, v) => s.copy(temp = v), minTemp, tempStep)

        override def metadata: Metadata =
          Metadata(
            room = room.map(_.value),
            manufacturer = Some(manufacturer),
            model = Some(model.value),
            id = Some(id),
            `type` = SwitchType.Bulb
          )
      }
  }
}
