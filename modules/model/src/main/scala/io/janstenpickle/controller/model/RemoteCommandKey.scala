package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{KeyDecoder, KeyEncoder}

case class RemoteCommandKey(source: Option[RemoteCommandSource], device: NonEmptyString, name: NonEmptyString)

object RemoteCommandKey {
  implicit val eq: Eq[RemoteCommandKey] = semi.eq

  implicit val remoteCommandKeyDecoder: KeyDecoder[RemoteCommandKey] = KeyDecoder.instance { value =>
    value.split(KeySeparator).toList match {
      case device :: name :: Nil =>
        (for {
          d <- refineV[NonEmpty](device)
          n <- refineV[NonEmpty](name)
        } yield RemoteCommandKey(None, d, n)).toOption
      case _ => None
    }
  }

  implicit val remoteCommandKeyEncoder: KeyEncoder[RemoteCommandKey] = KeyEncoder.encodeKeyString.contramap { rc =>
    s"${rc.device}$KeySeparator${rc.name}"
  }
}
