package io.janstenpickle.controller.tplink.device

import java.time.{DayOfWeek, Instant, ZoneOffset}
import java.util.concurrent.TimeUnit

import cats.data.NonEmptySet
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
import eu.timepit.refined.auto._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{ACursor, Decoder, DecodingFailure, Json}
import io.circe.syntax._
import io.janstenpickle.controller.model.{Room, State}
import io.janstenpickle.controller.schedule.model.{dayOfWeekOrdering, Days, Time}
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Metadata, Switch, SwitchType}
import io.janstenpickle.controller.tplink.Constants._
import io.janstenpickle.controller.tplink.TplinkClient
import cats.syntax.traverse._
import cats.instances.list._
import cats.instances.either._
import eu.timepit.refined.types.time.{Hour, Minute}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

sealed trait TplinkDevice[F[_]] extends Switch[F] {
  def room: Option[Room]
  def refresh: F[Unit]
  def rename(name: NonEmptyString, room: Option[Room]): F[Unit]
  def roomName: NonEmptyString = room.fold(name)(r => NonEmptyString.unsafeFrom(s"${r.value}_${name.value}"))
}

object TplinkDevice {

  trait SmartPlug[F[_]] extends TplinkDevice[F] {
    def scheduleAction(time: Time, state: State): F[String]
    def updateAction(id: String, time: Time, state: State): F[Unit]
    def scheduleInfo(id: String): F[Option[(NonEmptyString, NonEmptyString, Time, State)]]
    def deleteSchedule(id: String): F[Option[Unit]]
    def listSchedules: F[List[String]]
  }

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

  implicit final val decodeHour: Decoder[(Hour, Minute)] = Decoder.decodeInt.emap { int =>
    for {
      hour <- Hour.from(int / 60)
      minute <- Minute.from(int % 60)
    } yield (hour, minute)
  }

  implicit final val decodeDay: Decoder[Days] = Decoder[List[Int]].emap {
    case sun :: mon :: tue :: wed :: thur :: fri :: sat :: Nil =>
      def intToDay(v: Int, day: DayOfWeek): SortedSet[DayOfWeek] = if (v == 1) SortedSet(day) else SortedSet.empty

      NonEmptySet
        .fromSet(
          intToDay(sun, DayOfWeek.SUNDAY) ++ intToDay(mon, DayOfWeek.MONDAY) ++ intToDay(tue, DayOfWeek.TUESDAY) ++ intToDay(
            wed,
            DayOfWeek.WEDNESDAY
          ) ++ intToDay(thur, DayOfWeek.THURSDAY) ++ intToDay(fri, DayOfWeek.FRIDAY) ++ intToDay(sat, DayOfWeek.FRIDAY)
        )
        .fold[Either[String, NonEmptySet[DayOfWeek]]](Left("No days scheduled"))(Right(_))
    case _ => Left("Invalid day of week pattern")
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
  final val Schedule = "schedule"
  final val AddRule = "add_rule"
  final val EditRule = "edit_rule"

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

        private val dayOfWeekPattern: DayOfWeek => List[Int] = {
          case DayOfWeek.SUNDAY => List(1, 0, 0, 0, 0, 0, 0)
          case DayOfWeek.MONDAY => List(0, 1, 0, 0, 0, 0, 0)
          case DayOfWeek.TUESDAY => List(0, 0, 1, 0, 0, 0, 0)
          case DayOfWeek.WEDNESDAY => List(0, 0, 0, 1, 0, 0, 0)
          case DayOfWeek.THURSDAY => List(0, 0, 0, 0, 1, 0, 0)
          case DayOfWeek.FRIDAY => List(0, 0, 0, 0, 0, 1, 0)
          case DayOfWeek.SATURDAY => List(0, 0, 0, 0, 0, 0, 1)
        }

        private def daysPattern(days: Days) =
          days.toSortedSet.foldLeft(List.fill(7)(0)) {
            case (acc, day) =>
              acc.zip(dayOfWeekPattern(day)).map { case (x, y) => x + y }
          }

        private def hourMinute(hour: Hour, minute: Minute) = (hour * 60) + minute

        override def scheduleAction(time: Time, state: State): F[String] =
          tplink
            .sendCommand(
              s"""{"$Schedule":{"$AddRule":{"stime_opt":0,"wday":${daysPattern(time.days).asJson},"smin":${hourMinute(
                time.hourOfDay,
                time.minuteOfHour
              )},"enable":1,"repeat":1,"etime_opt":-1,"name":"controller","eact":-1,"month":0,"sact":${state.intValue},"year":0,"longitude":0,"day":0,"force":0,"latitude":0,"emin":0},"set_overall_enable":{"enable":1}}}"""
            )
            .flatMap { response =>
              val cursor = response.hcursor.downField(Schedule).downField(AddRule).downField("id")
              cursor.focus match {
                case None =>
                  tplink.parseSetResponse(Schedule, AddRule)(response) *> errors.missingJson(name, cursor.history)
                case Some(id) => id.as[String].fold(errors.decodingFailure(name, _), Applicative[F].pure)
              }
            }

        override def updateAction(id: String, time: Time, state: State): F[Unit] =
          tplink
            .sendCommand(
              s"""{"$Schedule":{"$EditRule":{"stime_opt":0,"wday":${daysPattern(time.days).asJson},"smin":${hourMinute(
                time.hourOfDay,
                time.minuteOfHour
              )},"enable":1,"repeat":1,"etime_opt":-1,"id":"$id","name":"lights on","eact":-1,"month":0,"sact":${state.intValue},"year":0,"longitude":0,"day":0,"force":0,"latitude":0,"emin":0}}}"""
            )
            .flatMap(tplink.parseSetResponse(Schedule, EditRule))

        override def deleteSchedule(id: String): F[Option[Unit]] =
          tplink
            .sendCommand(s"""{"$Schedule":{"delete_rule":{"id":"$id"}}}""")
            .map(
              _.hcursor
                .downField(Schedule)
                .downField("delete_rule")
                .get[Int]("err_code")
                .fold[Option[Unit]](_ => Some(()), code => if (code == -14) None else Some(()))
            )

        private def schedules: F[List[(String, Json)]] =
          tplink
            .sendCommand(s"""{"$Schedule":{"get_rules":null}}""")
            .flatMap { json =>
              json.hcursor
                .downField(Schedule)
                .downField("get_rules")
                .downField("rule_list")
                .as[List[Json]]
                .flatMap(_.traverse[Either[DecodingFailure, *], (String, Json)] { json =>
                  json.hcursor.get[String]("id").map(_ -> json)

                })
                .fold(errors.decodingFailure(name, _), Applicative[F].pure(_))
            }

        override def scheduleInfo(id: String): F[Option[(NonEmptyString, NonEmptyString, Time, State)]] =
          schedules.flatMap(_.collectFirst { case (i, json) if i == id => json } match {
            case None => Applicative[F].pure(None)
            case Some(json) =>
              val cursor = json.hcursor
              (for {
                day <- cursor.downField("wday").as[Days]
                (hour, minute) <- cursor.downField("smin").as[(Hour, Minute)]
                state <- cursor.downField("sact").as[State]
              } yield Some((device, name, Time(day, hour, minute), state)))
                .fold(errors.decodingFailure(name, _), Applicative[F].pure)
          })

        override def listSchedules: F[List[String]] = schedules.map(_.map(_._1))
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
