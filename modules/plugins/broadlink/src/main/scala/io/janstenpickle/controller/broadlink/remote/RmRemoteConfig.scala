package io.janstenpickle.controller.broadlink.remote

import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.types.string.NonEmptyString

import scala.concurrent.duration._

sealed trait RmRemoteConfig {
  def name: NonEmptyString
  def host: NonEmptyString
  def mac: Mac
  def timeout: FiniteDuration
}

object RmRemoteConfig {
  case class RM2(name: NonEmptyString, host: NonEmptyString, mac: Mac, timeout: FiniteDuration = 100.millis)
      extends RmRemoteConfig
  case class Mini3(name: NonEmptyString, host: NonEmptyString, mac: Mac, timeout: FiniteDuration = 100.millis)
      extends RmRemoteConfig
}
