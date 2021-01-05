package io.janstenpickle.controller.deconz.model

import cats.Eq
import cats.instances.string._
import io.circe.{Codec, Decoder, Encoder}

import scala.concurrent.duration.FiniteDuration

case class Event(e: String, id: String, r: String, state: State)

case class State(buttonevent: ButtonAction)

sealed trait ButtonAction {
  def stringValue: String
}

object ButtonAction {
  sealed trait Deconz extends ButtonAction {
    def intValue: Int
    override val stringValue: String = this.toString
  }
  case object On extends Deconz {
    override val intValue: Int = 1002
  }
  case object Off extends Deconz {
    override val intValue: Int = 2002
  }
  case object LongPressOnStart extends Deconz {
    override val intValue: Int = 1001
  }
  case object LongPressOnStop extends Deconz {
    override val intValue: Int = 1003
  }
  case object LongPressOffStart extends Deconz {
    override val intValue: Int = 2001
  }
  case object LongPressOffStop extends Deconz {
    override val intValue: Int = 2003
  }

  sealed trait LongPress extends ButtonAction {
    def duration: FiniteDuration
  }
  case class LongPressOn(duration: FiniteDuration) extends LongPress {
    override val stringValue: String = "LongPressOn"
  }
  case class LongPressOff(duration: FiniteDuration) extends LongPress {
    override val stringValue: String = "LongPressOff"
  }

  val fromInt: Int => Either[String, ButtonAction] = {
    case On.intValue => Right(On)
    case Off.intValue => Right(Off)
    case LongPressOnStart.intValue => Right(LongPressOnStart)
    case LongPressOnStop.intValue => Right(LongPressOnStop)
    case LongPressOffStart.intValue => Right(LongPressOffStart)
    case LongPressOffStop.intValue => Right(LongPressOffStop)
    case _ => Left("Could not decode button action from int")
  }

  implicit val buttonEventEq: Eq[ButtonAction] = Eq.by(_.stringValue)
}
