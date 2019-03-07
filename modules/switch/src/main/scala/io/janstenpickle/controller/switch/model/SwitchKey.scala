package io.janstenpickle.controller.switch.model

import eu.timepit.refined.types.string.NonEmptyString

case class SwitchKey(device: NonEmptyString, name: NonEmptyString)
