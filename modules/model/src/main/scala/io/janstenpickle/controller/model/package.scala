package io.janstenpickle.controller

import cats.Eq
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.generic.extras.Configuration

package object model {
  type Room = NonEmptyString

  implicit val circeConfig: Configuration = Configuration.default.withDefaults.withDiscriminator("type")
  implicit val nesEq: Eq[NonEmptyString] = Eq.by(_.value)
  final val DefaultRoom: Room = NonEmptyString("default")
  final val KeySeparator: Char = '|'
}
