package io.janstenpickle.controller.broadlink.switch

import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.types.string.NonEmptyString

import scala.concurrent.duration._

sealed trait SpSwitchConfig {
  def name: NonEmptyString
  def host: NonEmptyString
  def mac: Mac
  def timeout: FiniteDuration
}

object SpSwitchConfig {
  case class SP1(name: NonEmptyString, host: NonEmptyString, mac: Mac, timeout: FiniteDuration = 100.millis)
      extends SpSwitchConfig
  case class SP2(name: NonEmptyString, host: NonEmptyString, mac: Mac, timeout: FiniteDuration = 100.millis)
      extends SpSwitchConfig
  case class SP3(name: NonEmptyString, host: NonEmptyString, mac: Mac, timeout: FiniteDuration = 100.millis)
      extends SpSwitchConfig
}
