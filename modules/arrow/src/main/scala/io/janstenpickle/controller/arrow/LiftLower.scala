package io.janstenpickle.controller.arrow

import cats.~>

trait LiftLower[F[_], G[_]] { outer =>
  def lower: G ~> F
  def lift: F ~> G
  def imapK[H[_]](fh: G ~> H)(hf: H ~> G): LiftLower[F, H] = new LiftLower[F, H] {
    override val lower: H ~> F = λ[H ~> F](ha => outer.lower(hf(ha)))
    override val lift: F ~> H = λ[F ~> H](fa => fh(outer.lift(fa)))
  }
}

object LiftLower {
  def apply[F[_], G[_]](fg: F ~> G)(gf: G ~> F): LiftLower[F, G] = new LiftLower[F, G] {
    override val lower: G ~> F = gf
    override val lift: F ~> G = fg
  }
}
