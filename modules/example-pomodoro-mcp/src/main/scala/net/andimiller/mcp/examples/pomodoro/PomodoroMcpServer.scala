package net.andimiller.mcp.examples.pomodoro

import scala.concurrent.duration.*

import cats.effect.*
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.PromptArgument
import net.andimiller.mcp.core.protocol.PromptMessage
import net.andimiller.mcp.core.protocol.ResourceContent
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.schema.description
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.McpHttp
import net.andimiller.mcp.http4s.StreamingMcpHttpBuilder

import com.comcast.ip4s.*
import io.circe.Decoder
import io.circe.Encoder

// ── request / response types ────────────────────────────────────────

case class StartTimerRequest(
    @description("Duration of the pomodoro in minutes")
    duration_minutes: Int,
    @description("Label for this pomodoro session")
    label: String
) derives JsonSchema,
      Decoder

case class SleepBlockingRequest(
    @description("How long to block for, in seconds")
    duration_seconds: Int
) derives JsonSchema,
      Decoder

case class EmptyRequest() derives JsonSchema, Decoder

case class StatusResponse(status: String) derives Encoder.AsObject, JsonSchema

case class MessageResponse(message: String) derives Encoder.AsObject, JsonSchema

// ── shared builder setup ────────────────────────────────────────────

object PomodoroMcpServer extends IOApp.Simple:

  /** Wires all tools, resources, prompts and capabilities onto the given builder. Call `.stateful[PomodoroTimer]`
    * internally so callers only need a `Unit`-context builder.
    */
  def configure(builder: StreamingMcpHttpBuilder[IO, Unit]): StreamingMcpHttpBuilder[IO, PomodoroTimer] =
    builder
      .stateful[PomodoroTimer](ctx => PomodoroTimer.create(ctx.sink))
      // ── tools (all need the timer) ──────────────────────────────────
      .withContextualTool(
        contextualTool[PomodoroTimer]
          .name("start_timer")
          .description("Start a new pomodoro timer")
          .in[StartTimerRequest]
          .out[MessageResponse]
          .run((timer, req) => timer.start(req.duration_minutes, req.label).map(MessageResponse(_)))
      )
      .withContextualTool(
        contextualTool[PomodoroTimer]
          .name("pause_timer")
          .description("Pause the running pomodoro timer")
          .in[EmptyRequest]
          .out[MessageResponse]
          .run((timer, _) => timer.pause().map(MessageResponse(_)))
      )
      .withContextualTool(
        contextualTool[PomodoroTimer]
          .name("resume_timer")
          .description("Resume a paused pomodoro timer")
          .in[EmptyRequest]
          .out[MessageResponse]
          .run((timer, _) => timer.resume().map(MessageResponse(_)))
      )
      .withContextualTool(
        contextualTool[PomodoroTimer]
          .name("stop_timer")
          .description("Stop/cancel the current pomodoro timer")
          .in[EmptyRequest]
          .out[MessageResponse]
          .run((timer, _) => timer.stop().map(MessageResponse(_)))
      )
      .withContextualTool(
        contextualTool[PomodoroTimer]
          .name("get_status")
          .description("Get the current pomodoro timer status")
          .in[EmptyRequest]
          .out[StatusResponse]
          .run((timer, _) => timer.status.map(StatusResponse(_)))
      )
      .withContextualTool(
        contextualTool[PomodoroTimer]
          .name("sleep_blocking")
          .description(
            "Sleep for the given number of seconds. Demonstrates MCP cancellation: send notifications/cancelled and the tool's onCancel hook logs how long it actually slept."
          )
          .in[SleepBlockingRequest]
          .out[MessageResponse]
          .run { (timer, req) =>
            val duration = req.duration_seconds.seconds
            IO.monotonic.flatMap { started =>
              IO.sleep(duration)
                .onCancel(
                  IO.monotonic.flatMap { ended =>
                    val msg =
                      s"sleep_blocking cancelled after ${(ended - started).toMillis}ms (asked for ${duration.toMillis}ms)"
                    IO.println(msg) *> timer.log("info", msg)
                  }
                )
                .as(MessageResponse(s"slept for ${duration.toSeconds}s"))
            }
          }
      )
      // ── resources (need the timer) ─────────────────────────────────
      .withContextualResource(
        contextualResource[PomodoroTimer]
          .uri("pomodoro://status")
          .name("Timer Status")
          .description("Current pomodoro timer status (subscribable)")
          .mimeType("text/plain")
          .read(timer => timer.status)
      )
      .withContextualResource(
        contextualResource[PomodoroTimer]
          .uri("pomodoro://history")
          .name("Session History")
          .description("Completed pomodoro session history")
          .mimeType("text/plain")
          .read(timer => timer.historyText)
      )
      // ── resource templates (need the timer) ────────────────────────
      .withContextualResourceTemplate(
        contextualResourceTemplate[PomodoroTimer]
          .path(path.static("pomodoro://timers/") *> path.named("name"))
          .name("Timer by Name")
          .description("Look up a timer by its label")
          .mimeType("text/plain")
          .read { (timer, timerName) =>
            timer.statusForLabel(timerName).map {
              case Some(text) => ResourceContent.text(s"pomodoro://timers/$timerName", text, Some("text/plain"))
              case None       =>
                ResourceContent
                  .text(s"pomodoro://timers/$timerName", s"No timer found with label: $timerName", Some("text/plain"))
            }
          }
      )
      // ── prompts ────────────────────────────────────────────────────
      .withPrompts(
        Prompt
          .dynamic[IO](
            promptName = "plan_session",
            promptDescription = Some("Plan a focused work session with pomodoro timers"),
            promptArguments = List(
              PromptArgument("task", Some("The task to focus on"), required = true),
              PromptArgument("session_count", Some("Number of pomodoro sessions (default: 4)"), required = false)
            ),
            generator = { args =>
              val task  = args.get("task").flatMap(_.asString).getOrElse("unnamed task")
              val count = args.get("session_count").flatMap(_.asString).flatMap(_.toIntOption).getOrElse(4)
              IO.pure(
                List(
                  PromptMessage.user(
                    s"""|I want to plan a focused work session using the Pomodoro technique.
                        |
                        |Task: $task
                        |Number of pomodoro sessions: $count
                        |
                        |Please help me:
                        |1. Break down the task into $count sub-tasks (one per pomodoro)
                        |2. Suggest a duration for each session (25 min default)
                        |3. Recommend break activities between sessions
                        |4. Start the first timer when I'm ready
                        |""".stripMargin
                  )
                )
              )
            }
          )
          .resolve
      )
      .withContextualPrompt(
        contextualPrompt[PomodoroTimer]
          .name("review_day")
          .description("Review completed pomodoro sessions for the day")
          .generate { (timer, _) =>
            timer.historyText.map { historyStr =>
              List(
                PromptMessage.user(
                  s"""|Please review my pomodoro sessions for today and provide feedback.
                      |
                      |Session history:
                      |$historyStr
                      |
                      |Please analyze:
                      |1. Total focused time
                      |2. Session patterns (length, frequency)
                      |3. Suggestions for improvement
                      |""".stripMargin
                )
              )
            }
          }
      )
      .enableResourceSubscriptions
      .enableLogging

  // ── server ──────────────────────────────────────────────────────────

  final def run: IO[Unit] =
    configure(
      McpHttp
        .streaming[IO]
        .name("pomodoro-mcp")
        .version("1.0.0")
        .port(port"25000")
        .withExplorer(redirectToRoot = true)
    ).serve.useForever
