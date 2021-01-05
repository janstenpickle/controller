package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Codec, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto._
import io.circe.refined._

case class RemoteSwitchKey(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString) {
  lazy val toSwitchKey: SwitchKey =
    SwitchKey(NonEmptyString.unsafeFrom(s"$remote-$device"), name)
}

object RemoteSwitchKey {
  implicit val eq: Eq[RemoteSwitchKey] = semi.eq

  implicit val remoteSwitchKeyDecoder: KeyDecoder[RemoteSwitchKey] = KeyDecoder.instance { value =>
    value.split(KeySeparator).toList match {
      case remote :: device :: name :: Nil =>
        (for {
          r <- refineV[NonEmpty](remote)
          d <- refineV[NonEmpty](device)
          n <- refineV[NonEmpty](name)
        } yield RemoteSwitchKey(r, d, n)).toOption
      case _ => None
    }
  }

  implicit val remoteSwitchKeyEncoder: KeyEncoder[RemoteSwitchKey] = KeyEncoder.encodeKeyString.contramap { sk =>
    s"${sk.remote}$KeySeparator${sk.device}$KeySeparator${sk.name}"
  }

  implicit val remoteSwitchKeyCodec: Codec[RemoteSwitchKey] = deriveCodec
}
