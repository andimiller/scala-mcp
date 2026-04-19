package net.andimiller.mcp.examples.pomodoro

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.protocol.{PromptArgument, PromptMessage, ResourceContent}
import net.andimiller.mcp.core.schema.{JsonSchema, description}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.McpHttp

object PomodoroMcpServer extends IOApp.Simple, McpDsl[IO]:

  // ── request / response types ──────────────────────────────────────

  case class StartTimerRequest(
    @description("Duration of the pomodoro in minutes")
    duration_minutes: Int,
    @description("Label for this pomodoro session")
    label: String
  ) derives JsonSchema, Decoder

  case class EmptyRequest() derives JsonSchema, Decoder
  case class StatusResponse(status: String) derives Encoder.AsObject, JsonSchema
  case class MessageResponse(message: String) derives Encoder.AsObject, JsonSchema

  // ── server ──────────────────────────────────────────────────────────

  final def run: IO[Unit] =
    McpHttp.streaming[IO]
      .name("pomodoro-mcp")
      .version("1.0.0")
      .port(port"25000")
      .withExplorer(redirectToRoot = true)
      .stateful[PomodoroTimer](sink => PomodoroTimer.create(sink))
      // ── tools (all need the timer) ──────────────────────────────────
      .withContextualTool(
        contextualTool[PomodoroTimer].name("start_timer")
          .description("Start a new pomodoro timer")
          .in[StartTimerRequest]
          .run((timer, req) => timer.start(req.duration_minutes, req.label).map(MessageResponse(_))),
      )
      .withContextualTool(
        contextualTool[PomodoroTimer].name("pause_timer")
          .description("Pause the running pomodoro timer")
          .in[EmptyRequest]
          .run((timer, _) => timer.pause().map(MessageResponse(_))),
      )
      .withContextualTool(
        contextualTool[PomodoroTimer].name("resume_timer")
          .description("Resume a paused pomodoro timer")
          .in[EmptyRequest]
          .run((timer, _) => timer.resume().map(MessageResponse(_))),
      )
      .withContextualTool(
        contextualTool[PomodoroTimer].name("stop_timer")
          .description("Stop/cancel the current pomodoro timer")
          .in[EmptyRequest]
          .run((timer, _) => timer.stop().map(MessageResponse(_))),
      )
      .withContextualTool(
        contextualTool[PomodoroTimer].name("get_status")
          .description("Get the current pomodoro timer status")
          .in[EmptyRequest]
          .run((timer, _) => timer.status.map(StatusResponse(_))),
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
              case None       => ResourceContent.text(s"pomodoro://timers/$timerName", s"No timer found with label: $timerName", Some("text/plain"))
            }
          }
      )
      // ── prompts ────────────────────────────────────────────────────
      .withPrompts(
        Prompt.dynamic[IO](
          promptName = "plan_session",
          promptDescription = Some("Plan a focused work session with pomodoro timers"),
          promptArguments = List(
            PromptArgument("task", Some("The task to focus on"), required = true),
            PromptArgument("session_count", Some("Number of pomodoro sessions (default: 4)"), required = false)
          ),
          generator = { args =>
            val task = args.get("task").flatMap(_.asString).getOrElse("unnamed task")
            val count = args.get("session_count").flatMap(_.asString).flatMap(_.toIntOption).getOrElse(4)
            IO.pure(List(
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
            ))
          }
        ).resolve
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
      .serve
      .useForever
