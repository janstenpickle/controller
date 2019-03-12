package io.janstenpickle.controller.broadlink.remote

import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.refineMV
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString

sealed trait RmRemoteConfig {
  def name: NonEmptyString
  def host: NonEmptyString
  def mac: Mac
  def timeoutMillis: PosInt
}

object RmRemoteConfig {
  case class RM2(name: NonEmptyString, host: NonEmptyString, mac: Mac, timeoutMillis: PosInt = refineMV(100))
      extends RmRemoteConfig
  case class Mini3(name: NonEmptyString, host: NonEmptyString, mac: Mac, timeoutMillis: PosInt = refineMV(100))
      extends RmRemoteConfig
}
