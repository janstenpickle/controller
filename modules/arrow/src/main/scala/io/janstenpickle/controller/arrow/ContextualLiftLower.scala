package io.janstenpickle.controller.arrow

import cats.~>

trait ContextualLiftLower[F[_], G[_], A] extends LiftLower[F, G] { outer =>
  def lower(a: A): G ~> F
  def lift(a: A): F ~> G

  override def imapK[H[_]](fh: G ~> H)(hf: H ~> G): ContextualLiftLower[F, H, A] = new ContextualLiftLower[F, H, A] {
    override def lower(a: A): H ~> F = λ[H ~> F](ha => outer.lower(a)(hf(ha)))
    override def lift(a: A): F ~> H = λ[F ~> H](fa => fh(outer.lift(a)(fa)))
    override val lower: H ~> F = λ[H ~> F](ha => outer.lower(hf(ha)))
    override val lift: F ~> H = λ[F ~> H](fa => fh(outer.lift(fa)))
  }
}

object ContextualLiftLower {
  def apply[F[_], G[_], A](fg: F ~> G, afg: A => F ~> G)(gf: G ~> F, agf: A => G ~> F): ContextualLiftLower[F, G, A] =
    new ContextualLiftLower[F, G, A] {
      override def lower(a: A): G ~> F = agf(a)
      override def lift(a: A): F ~> G = afg(a)
      override val lower: G ~> F = gf
      override val lift: F ~> G = fg
    }
}
