package io.janstenpickle.controller.stats.prometheus

import java.util.concurrent.ConcurrentHashMap

import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import cats.syntax.applicative._
import cats.effect.syntax.concurrent._
import cats.syntax.apply._
import cats.syntax.applicativeError._
import cats.syntax.functor._
import cats.syntax.flatMap._
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Pipe
import io.janstenpickle.controller.stats.Stats
import io.janstenpickle.controller.stats.Stats._
import io.prometheus.client.{CollectorRegistry, Counter, Gauge}

import scala.concurrent.duration._
import scala.util.Try

object MetricsSink {

  def apply[F[_]: Concurrent: ContextShift: Timer](
    registry: CollectorRegistry,
    blocker: Blocker,
    parallelism: PosInt = PosInt(2),
    timeout: FiniteDuration = 500.millis
  ): Pipe[F, Stats, Unit] = { stream =>
    implicit class GaugeSyntax(gauge: F[Gauge]) {
      def set[A](labels: String*)(value: A)(implicit num: Numeric[A]): F[Unit] =
        gauge
          .flatMap(g => blocker.delay(g.labels(labels: _*).set(num.toDouble(value))))
          .handleError(_.printStackTrace)

      def setMany[A](labels: String*)(values: Map[NonEmptyString, A])(implicit num: Numeric[A]): F[Unit] =
        gauge.flatMap(
          g =>
            blocker.delay {
              values.foreach { case (k, v) => g.labels(labels :+ k.value: _*).set(num.toDouble(v)) }
          }
        )

      def setManyNested[A](
        labels: String*
      )(values: Map[NonEmptyString, Map[NonEmptyString, A]])(implicit num: Numeric[A]): F[Unit] =
        gauge.flatMap(
          g =>
            blocker.delay {
              values.foreach {
                case (k0, v0) =>
                  v0.foreach { case (k1, v1) => g.labels(labels :+ k0.value :+ k1.value: _*).set(num.toDouble(v1)) }
              }
          }
        )
    }

    implicit class CounterSyntax(counter: F[Counter]) {
      def incByMany[A](labels: String*)(values: Map[NonEmptyString, A])(implicit num: Numeric[A]): F[Unit] =
        counter.flatMap(
          c =>
            blocker.delay {
              values.foreach { case (k, v) => c.labels(labels :+ k.value: _*).inc(num.toDouble(v)) }
          }
        )

      def incBy[A](labels: String*)(value: A)(implicit num: Numeric[A]): F[Unit] =
        counter
          .flatMap(g => blocker.delay(g.labels(labels: _*).inc(num.toDouble(value))))
          .handleError(_.printStackTrace)
      def inc(labels: String*): F[Unit] =
        counter.flatMap(g => blocker.delay(g.labels(labels: _*).inc())).handleError(_.printStackTrace)
    }

    def makeKey(name: String, labels: String*) = (name :: labels.toList).mkString("_")

    val gauges = new ConcurrentHashMap[String, Gauge]()
    val counters = new ConcurrentHashMap[String, Counter]()

    def getOrCreateGauge(name: String, help: String, labels: String*): F[Gauge] =
      blocker.delay {
        val key = makeKey(name, labels: _*)
        val gauge = gauges.getOrDefault(key, Gauge.build(name, help).labelNames(labels: _*).create())
        // doesn't matter if it throws an error saying already registered
        Try(gauge.register(registry))
        gauges.put(key, gauge)
        gauge
      }

    def getOrCreateCounter(name: String, help: String, labels: String*): F[Counter] =
      blocker.delay {
        val key = makeKey(name, labels: _*)
        val counter = counters.getOrDefault(key, Counter.build(name, help).labelNames(labels: _*).create())
        // doesn't matter if it throws an error saying already registered
        Try(counter.register(registry))
        counters.put(key, counter)
        counter
      }

    def errorCounter(errors: Int, configSource: String): F[Unit] =
      getOrCreateCounter("config_source_errors", "Errors encountered when reading from config source", "config_source")
        .incBy(configSource)(errors)

    val statsToRegistry: Stats => F[Unit] = {
      case Empty => ().pure[F]

      // Activity stats
      case SetActivity(room, activity) =>
        getOrCreateCounter("set_activty", "Set current activity for a room", "room", "activity")
          .inc(room.value, activity.value)
      case Activities(errorCount, activityCount, contextButtons) =>
        errorCounter(errorCount, "activities") *>
          getOrCreateGauge("activities", "Number of activities broken down by room", "room").setMany()(activityCount) *>
          getOrCreateGauge(
            "activity_context_buttons",
            "Number of context buttons for an activity broken down by room and activity",
            "room",
            "activity"
          ).setManyNested()(contextButtons)

      // Button stats
      case Buttons(errorCount, buttons) =>
        errorCounter(errorCount, "buttons") *>
          getOrCreateGauge("buttons", "Number of common buttons by room and button type", "room", "button_type")
            .setManyNested()(buttons)

      // Macro stats
      case Macro(name, commands) =>
        getOrCreateGauge("macros", "Macro commands breakdown", "macro_name", "command_type")
          .setMany(name.value)(commands)
      case StoreMacro(name, commands) =>
        getOrCreateCounter("store_macro", "Macros stored with command break down", "macro_name", "command_type")
          .incByMany(name.value)(commands)
      case ExecuteMacro(name) =>
        getOrCreateCounter("execute_macro", "Records execution of a macro", "macro_name").inc(name.value)
      case ExecuteCommand(commandType) =>
        getOrCreateCounter("execute_command", "Records the execution of a command", "command_type").inc(
          commandType.value
        )

      // Remote stats
      case SendRemoteCommand(remote, device, name) =>
        getOrCreateCounter(
          "send_remote_command",
          "Records sending of a remote control command",
          "remote_name",
          "device_name",
          "command"
        ).inc(remote.value, device.value, name.value)
      case LearnRemoteCommand(remote, device, name) =>
        getOrCreateCounter(
          "learn_remote_command",
          "Records learning of a remote control command",
          "remote_name",
          "device_name",
          "command"
        ).inc(remote.value, device.value, name.value)
      case Remotes(errorCount, remoteCount, remoteRoomActivityCount, remoteButtons) =>
        errorCounter(errorCount, "remotes") *>
          getOrCreateGauge("remotes", "Total number of configured remotes").set()(remoteCount) *>
          getOrCreateGauge(
            "remotes_room_activity",
            "Number of remotes broken down by room and activity, note that a remote can belong to more than one room and activity",
            "room",
            "activity"
          ).setManyNested()(remoteRoomActivityCount) *>
          getOrCreateGauge("remote_buttons", "Button types associated with each remote", "remote_name", "button_type")
            .setManyNested()(remoteButtons)

      // Switch stats
      case SwitchState(key, state) =>
        getOrCreateGauge("switch_state", "Switch States 0.0 is off 1.0 is on", "device", "switch_name")
          .set(key.device.value, key.name.value)(state.intValue)
      case SwitchOn(device, name) =>
        getOrCreateCounter("switch_on", "Records switch being turned on", "device", "switch_name")
          .inc(device.value, name.value)
      case SwitchOff(device, name) =>
        getOrCreateCounter("switch_off", "Records switch being turned off", "device", "switch_name")
          .inc(device.value, name.value)
      case SwitchToggle(device, name) =>
        getOrCreateCounter("switch_toggle", "Records switch being toggled", "device", "switch_name")
          .inc(device.value, name.value)
    }

    def recordStats(stats: Stats): F[Unit] =
      statsToRegistry(stats).timeout(timeout).handleError(_.printStackTrace).start.void

    stream.parEvalMapUnordered(parallelism.value)(recordStats)
  }
}
