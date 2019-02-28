package io.janstenpickle.controller.switch

import eu.timepit.refined.types.string.NonEmptyString

case class SwitchKey(device: NonEmptyString, name: NonEmptyString)
