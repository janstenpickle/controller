package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.boolean._
import cats.instances.string._
import cats.instances.option._
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

sealed trait Button {
  def name: NonEmptyString
  def newRow: Option[Boolean]
  def colored: Option[Boolean]
  def align: Option[NonEmptyString]
}

object Button {
  implicit val eq: Eq[Button] = semi.eq

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
    colored: Option[Boolean],
    align: Option[NonEmptyString]
  ) extends Remote

  case class RemoteLabel(
    remote: NonEmptyString,
    device: NonEmptyString,
    name: NonEmptyString,
    label: NonEmptyString,
    newRow: Option[Boolean],
    colored: Option[Boolean],
    align: Option[NonEmptyString]
  ) extends Remote

  sealed trait Switch extends Button {
    def device: NonEmptyString
    def isOn: Boolean
  }

  case class SwitchIcon(
    name: NonEmptyString,
    device: NonEmptyString,
    icon: NonEmptyString,
    isOn: Boolean = false,
    newRow: Option[Boolean],
    colored: Option[Boolean],
    align: Option[NonEmptyString]
  ) extends Switch

  case class SwitchLabel(
    name: NonEmptyString,
    device: NonEmptyString,
    label: NonEmptyString,
    isOn: Boolean = false,
    newRow: Option[Boolean],
    colored: Option[Boolean],
    align: Option[NonEmptyString]
  ) extends Switch

  sealed trait Macro extends Button {
    def isOn: Option[Boolean]
  }

  case class MacroIcon(
    name: NonEmptyString,
    icon: NonEmptyString,
    isOn: Option[Boolean],
    newRow: Option[Boolean],
    colored: Option[Boolean],
    align: Option[NonEmptyString]
  ) extends Macro

  case class MacroLabel(
    name: NonEmptyString,
    label: NonEmptyString,
    isOn: Option[Boolean],
    newRow: Option[Boolean],
    colored: Option[Boolean],
    align: Option[NonEmptyString]
  ) extends Macro

  sealed trait Context extends Button

  case class ContextIcon(
    name: NonEmptyString,
    icon: NonEmptyString,
    newRow: Option[Boolean],
    colored: Option[Boolean],
    align: Option[NonEmptyString]
  ) extends Context

  case class ContextLabel(
    name: NonEmptyString,
    label: NonEmptyString,
    newRow: Option[Boolean],
    colored: Option[Boolean],
    align: Option[NonEmptyString]
  ) extends Context
}

case class Buttons(buttons: List[Button], errors: List[String] = List.empty)

object Buttons {
  implicit val eq: Eq[Buttons] = semi.eq
}
