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

  case class RemoteIcon(
    remote: NonEmptyString,
    device: NonEmptyString,
    name: NonEmptyString,
    icon: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Remote

  case class RemoteLabel(
    remote: NonEmptyString,
    device: NonEmptyString,
    name: NonEmptyString,
    label: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Remote

  sealed trait Switch extends Button

  case class SwitchIcon(name: NonEmptyString, icon: NonEmptyString, newRow: Option[Boolean], coloured: Option[Boolean])
      extends Switch

  case class SwitchLabel(
    name: NonEmptyString,
    label: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Switch

  sealed trait Macro extends Button

  case class MacroIcon(name: NonEmptyString, icon: NonEmptyString, newRow: Option[Boolean], coloured: Option[Boolean])
      extends Macro

  case class MacroLabel(name: NonEmptyString, label: NonEmptyString, newRow: Option[Boolean], coloured: Option[Boolean])
      extends Macro

  sealed trait Context extends Button

  case class ContextIcon(name: NonEmptyString, icon: NonEmptyString, newRow: Option[Boolean], coloured: Option[Boolean])
      extends Context

  case class ContextLabel(
    name: NonEmptyString,
    label: NonEmptyString,
    newRow: Option[Boolean],
    coloured: Option[Boolean]
  ) extends Context
}

case class Buttons(buttons: List[Button], error: Option[String])
