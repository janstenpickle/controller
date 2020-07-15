package io.janstenpickle.controller.schedule.cron

import java.nio.file.Path
import java.time.DayOfWeek

import cats.data.NonEmptyList
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.effect.syntax.concurrent._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cron4s.Cron
import eu.timepit.fs2cron.awakeEveryCron
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.chrisdavenport.fuuid.FUUID
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.CommandEvent
import io.janstenpickle.controller.schedule.{NamedScheduler, Scheduler}
import io.janstenpickle.controller.schedule.model.Schedule
import io.janstenpickle.trace4cats.inject.Trace

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

object CronScheduler {
  case class Config(configDir: Path, polling: PollingConfig, writeTimeout: FiniteDuration = 10.seconds)

  def apply[F[_]: ContextShift, G[_]: Concurrent: Timer: ContextShift](
    config: Config,
    commandPublisher: EventPublisher[F, CommandEvent],
    blocker: Blocker
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, Scheduler[F]] =
    for {
      source <- ConfigFileSource
        .polling[F, G](config.configDir.resolve("schedule"), config.polling.pollInterval, blocker, config.writeTimeout)
      schedules <- CirceScheduleConfigSource[F, G](source, config.polling)
      scheduler <- apply[F, G](commandPublisher, schedules)
    } yield scheduler

  def apply[F[_], G[_]](
    commandPublisher: EventPublisher[F, CommandEvent],
    store: WritableConfigSource[F, String, Schedule]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, Scheduler[F]] =
    Resource
      .liftF(for {
        logger <- Slf4jLogger.create[F]
        signal <- SignallingRef[F, Boolean](false)
      } yield (logger, signal))
      .flatMap {
        case (logger, signal) =>
          def stream: Stream[F, NonEmptyList[Unit]] =
            Stream
              .eval(store.getConfig.map(_.values.toSeq))
              .flatMap { schedules =>
                if (schedules.isEmpty)
                  Stream.awakeDelay[F](1.minute).flatMap(_ => Stream.emits[F, (String, Schedule)](schedules))
                else Stream.emits[F, (String, Schedule)](schedules)
              }
              .evalMap {
                case (id, schedule) =>
                  val days: SortedSet[DayOfWeek] = schedule.time.days.toSortedSet

                  F.fromEither(
                      Cron
                        .parse(s"0 ${schedule.time.minuteOfHour} ${schedule.time.hourOfDay} ? * ${days
                          .map(_.toString.toLowerCase().take(3))
                          .mkString(",")} *")
                    )
                    .flatMap { cron =>
                      logger
                        .info(s"Setting up '$id' schedule '${cron.show}' with action ${schedule.commands}")
                        .as(
                          awakeEveryCron(cron) >> Stream.eval(logger.info(s"Running schedule '$id'")) >> Stream
                            .eval(liftLower.lift(liftLower.lower("run.schedule") {
                              trace.put("id", id) >> schedule.commands.traverse { cmd =>
                                commandPublisher.publish1(CommandEvent.MacroCommand(cmd))
                              }
                            }))
                        )
                    }

              }
              .parJoinUnbounded
              .interruptWhen(signal)
              .onComplete(stream)
              .handleErrorWith(th => Stream.eval(logger.error(th)("Cron Scheduler failed")) >> stream)

          stream.compile.drain.background
            .map { _ =>
              new NamedScheduler[F] {
                override val name: String = "cron"

                override def create(schedule: Schedule): F[Option[String]] = trace.span("create.schedule") {
                  FUUID.randomFUUID.flatMap { uuid =>
                    val id = uuid.show
                    trace.put("id", id) >> store.upsert(id, schedule) >> signal
                      .set(true) >> signal.set(false).as(Some(id))
                  }
                }

                override def update(id: String, schedule: Schedule): F[Option[Unit]] = trace.span("update.schedule") {
                  trace.put("id", id) >> store.getValue(id).flatMap {
                    case None => F.pure(None)
                    case Some(_) => store.upsert(id, schedule) >> signal.set(true) >> signal.set(false).as(Some(()))
                  }
                }

                override def delete(id: String): F[Option[Unit]] = trace.span("delete.schedule") {
                  trace.put("id", id) >> store.getValue(id).flatMap {
                    case None => F.pure(None)
                    case Some(_) => store.deleteItem(id) >> signal.set(true) >> signal.set(false).as(Some(()))
                  }
                }

                override def info(id: String): F[Option[Schedule]] = trace.span("info.schedule") {
                  trace.put("id", id) >> store.getValue(id)
                }

                override def list: F[Set[(String, String)]] = trace.span("list.schedule") {
                  store.listKeys.map(_.map(name -> _)).flatTap { schedules =>
                    trace.put("schedule.count", schedules.size)
                  }
                }
              }
            }
      }
}
