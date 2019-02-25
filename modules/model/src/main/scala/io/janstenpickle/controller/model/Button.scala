package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

sealed trait Button {
  def name: NonEmptyString
  def newRow: Option[Boolean]
  def coloured: Option[Boolean]
}

object Button {
  sealed trait Remote extends Button {
    def remote: NonEmptyString
    def device: NonEmptyString
  }

  case class RemoteButtonIcon(
    remote: NonEmptyString,
    device: NonEmptyString,
    name: NonEmptyString,
    icon: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Remote

  case class RemoteButtonLabel(
    remote: NonEmptyString,
    device: NonEmptyString,
    name: NonEmptyString,
    label: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Remote

  sealed trait Switch extends Button

  case class SwitchButtonIcon(
    name: NonEmptyString,
    icon: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Switch

  case class SwitchButtonLabel(
    name: NonEmptyString,
    label: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Switch

  sealed trait Macro extends Button

  case class MacroButtonIcon(
    name: NonEmptyString,
    icon: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Macro

  case class MacroButtonLabel(
    name: NonEmptyString,
    label: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Macro
}
