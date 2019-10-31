package io.janstenpickle.controller.stats.`macro`

import cats.data.NonEmptyList
import cats.effect.{Concurrent, Resource}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import fs2.concurrent.Topic
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.stats._

import scala.collection.compat._

trait MacroStatsRecorder[F[_]] {
  def recordStoreMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit]
  def recordExecuteMacro(name: NonEmptyString): F[Unit]
  def recordExecuteCommand(command: Command): F[Unit]
}

object MacroStatsRecorder {
  def stream[F[_]: Concurrent](maxQueued: PosInt): Resource[F, (MacroStatsRecorder[F], fs2.Stream[F, Stats])] = {
    def make(topic: Topic[F, Stats]): MacroStatsRecorder[F] = new MacroStatsRecorder[F] {
      override def recordStoreMacro(name: NonEmptyString, commands: NonEmptyList[Command]): F[Unit] =
        topic.publish1(
          Stats.StoreMacro(
            name,
            commands
              .groupBy(commandType)
              .view
              .mapValues(_.size)
              .toMap
          )
        )

      override def recordExecuteMacro(name: NonEmptyString): F[Unit] =
        topic.publish1(Stats.ExecuteMacro(name))

      override def recordExecuteCommand(command: Command): F[Unit] =
        topic.publish1(Stats.ExecuteCommand(commandType(command)))
    }

    StatsTopic[F](maxQueued).map {
      case (topic, stream) =>
        make(topic) -> stream
    }
  }
}
