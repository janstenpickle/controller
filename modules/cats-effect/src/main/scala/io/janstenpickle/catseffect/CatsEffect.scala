package io.janstenpickle.catseffect

import java.util.concurrent.Executors

import cats.effect.{ContextShift, Resource, Sync}

import scala.concurrent.ExecutionContext

object CatsEffect {
  def suspendErrors[F[_], A](thunk: => A)(implicit F: Sync[F]): F[A] = F.suspend(F.catchNonFatal(thunk))

  def evalOn[F[_], A](fa: F[A], ec: ExecutionContext)(implicit cs: ContextShift[F]): F[A] = cs.evalOn(ec)(fa)

  def suspendErrorsEvalOn[F[_]: Sync: ContextShift, A](thunk: => A, ec: ExecutionContext): F[A] =
    evalOn(suspendErrors(thunk), ec)

  def cachedExecutorResource[F[_]: Sync: ContextShift]: Resource[F, ExecutionContext] =
    Resource
      .make(suspendErrors(Executors.newCachedThreadPool()))(es => suspendErrors(es.shutdown()))
      .map(ExecutionContext.fromExecutor)
}
