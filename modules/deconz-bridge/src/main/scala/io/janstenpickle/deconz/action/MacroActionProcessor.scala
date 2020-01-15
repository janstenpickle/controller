package io.janstenpickle.deconz.action

import cats.effect.{Concurrent, Timer}
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.errors.ErrorHandler
import io.janstenpickle.deconz.config.{ActionMapping, ControllerAction}
import io.janstenpickle.deconz.model.ButtonAction
import natchez.Trace
import natchez.TraceValue.NumberValue

import scala.concurrent.duration._

object MacroActionProcessor {
  def apply[F[_]: Concurrent: Timer](
    config: ConfigSource[F, String, Set[ActionMapping]],
    `macro`: Macro[F],
    context: Context[F]
  )(implicit trace: Trace[F], errorHandler: ErrorHandler[F]): F[ActionProcessor[F]] = Slf4jLogger.create[F].map {
    logger =>
      new ActionProcessor[F] {
        override def process(id: String, action: ButtonAction): F[Unit] = {
          val exec: Option[ControllerAction] => F[Unit] = {
            case Some(ControllerAction.Macro(cmd)) => `macro`.executeCommand(cmd) >> trace.put("action.present" -> true)
            case Some(ControllerAction.Context(room, name)) =>
              context.action(room, name) >> trace.put("action.present" -> true)
            case None => trace.put("action.present" -> false)
          }

          val fa = trace.span("deconz.action.processor") {
            trace.put("id" -> id, "action" -> action.stringValue) >>
              Concurrent.timeout(
                config.getValue(id).flatMap {
                  case Some(mappings) =>
                    action match {
                      case ButtonAction.LongPressOn(duration) =>
                        trace.put("duration" -> NumberValue(duration.toMillis)) >> exec(
                          mappings.toList
                            .sortBy {
                              case ActionMapping(ButtonAction.LongPressOn(d), _) => d
                              case _ => 0.millis
                            }(FiniteDuration.FiniteDurationIsOrdered.reverse)
                            .collectFirst {
                              case ActionMapping(ButtonAction.LongPressOn(d), controller) if duration >= d => controller
                            }
                        )
                      case ButtonAction.LongPressOff(duration) =>
                        trace.put("duration" -> NumberValue(duration.toMillis)) >> exec(
                          mappings.toList
                            .sortBy {
                              case ActionMapping(ButtonAction.LongPressOff(d), _) => d
                              case _ => 0.millis
                            }(FiniteDuration.FiniteDurationIsOrdered.reverse)
                            .collectFirst {
                              case ActionMapping(ButtonAction.LongPressOff(d), controller) if duration >= d =>
                                controller
                            }
                        )
                      case action @ _ => exec(mappings.collectFirst { case m if m.button == action => m.controller })
                    }
                  case None =>
                    logger.warn(s"Could not find mapping for button id '$id'") >> trace
                      .put("error" -> true, "mapping.present" -> false)
                },
                1.minute
              )
          }

          errorHandler.handleWith(fa)(logger.warn(_)("Failed to execute button action"))
        }

      }
  }
}
