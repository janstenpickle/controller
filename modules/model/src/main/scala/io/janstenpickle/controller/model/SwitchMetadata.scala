package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.map._
import cats.instances.option._
import cats.instances.string._

case class SwitchMetadata(
  room: Option[String] = None,
  manufacturer: Option[String] = None,
  model: Option[String] = None,
  host: Option[String] = None,
  id: Option[String] = None,
  `type`: SwitchType = SwitchType.Switch,
  others: Map[String, String] = Map.empty
) {
  import SwitchMetadata.Keys._

  lazy val values: Map[String, String] = others + (Type -> `type`.toString.toLowerCase) ++ room.map(Room -> _) ++ manufacturer
    .map(Manufacturer -> _) ++ model.map(Model -> _) ++ host.map(Host -> _) ++ id.map(Id -> _)
}

object SwitchMetadata {
  object Keys {
    final val Room = "room"
    final val Manufacturer = "manufacturer"
    final val Model = "model"
    final val Host = "host"
    final val Id = "id"
    final val Type = "type"
  }

  implicit val eq: Eq[SwitchMetadata] = cats.derived.semi.eq
}
