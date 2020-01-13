package io.janstenpickle.deconz.action

import cats.effect.Sync
import cats.syntax.functor._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.deconz.model.ButtonAction

object LogActionProcessor {
  def apply[F[_]: Sync]: F[ActionProcessor[F]] = Slf4jLogger.create[F].map { logger =>
    new ActionProcessor[F] {
      override def process(id: String, action: ButtonAction): F[Unit] =
        logger.info(s"Received action from $id $action")
    }
  }
}
