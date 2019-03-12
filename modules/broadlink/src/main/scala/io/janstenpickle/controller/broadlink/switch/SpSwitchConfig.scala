package io.janstenpickle.controller.broadlink.switch

import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.types.string.NonEmptyString

sealed trait SpSwitchConfig {
  def name: NonEmptyString
  def host: NonEmptyString
  def mac: Mac
}

object SpSwitchConfig {
  case class SP1(name: NonEmptyString, host: NonEmptyString, mac: Mac) extends SpSwitchConfig
  case class SP2(name: NonEmptyString, host: NonEmptyString, mac: Mac) extends SpSwitchConfig
}
