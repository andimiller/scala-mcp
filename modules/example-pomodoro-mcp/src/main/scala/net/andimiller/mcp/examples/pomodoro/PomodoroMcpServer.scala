package net.andimiller.mcp.examples.pomodoro

import cats.effect.{IO, Resource}
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.protocol.{PromptArgument, PromptMessage, ResourceContent}
import net.andimiller.mcp.core.schema.{JsonSchema, description}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.HttpMcpStatefulResourceApp

/**
 * Pomodoro MCP server demonstrating:
 *  - Dynamic resources with subscription notifications
 *  - Server-initiated notifications (resource updates, logging)
 *  - Dynamic prompts with arguments
 *  - Streamable HTTP transport with per-session state
 */
object PomodoroMcpServer extends HttpMcpStatefulResourceApp[Unit, PomodoroTimer]:

  // ── config ──────────────────────────────────────────────────────────

  def serverName    = "pomodoro-mcp"
  def serverVersion = "1.0.0"
  override def port = port"25000"
  override def explorerEnabled = true
  override def rootRedirectToExplorer = true

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

  // ── resources ───────────────────────────────────────────────────────

  def mkResources = Resource.pure(())

  def mkSessionResources(r: Unit, sink: NotificationSink[IO]): IO[PomodoroTimer] =
    PomodoroTimer.create(sink)

  // ── tools ─────────────────────────────────────────────────────────

  override def tools(r: Unit, timer: PomodoroTimer) = List(
    Tool.builder[IO]
      .name("start_timer")
      .description("Start a new pomodoro timer")
      .in[StartTimerRequest]
      .run { req =>
        timer.start(req.duration_minutes, req.label).map(MessageResponse(_))
      },

    Tool.builder[IO]
      .name("pause_timer")
      .description("Pause the running pomodoro timer")
      .in[EmptyRequest]
      .run { _ =>
        timer.pause().map(MessageResponse(_))
      },

    Tool.builder[IO]
      .name("resume_timer")
      .description("Resume a paused pomodoro timer")
      .in[EmptyRequest]
      .run { _ =>
        timer.resume().map(MessageResponse(_))
      },

    Tool.builder[IO]
      .name("stop_timer")
      .description("Stop/cancel the current pomodoro timer")
      .in[EmptyRequest]
      .run { _ =>
        timer.stop().map(MessageResponse(_))
      },

    Tool.builder[IO]
      .name("get_status")
      .description("Get the current pomodoro timer status")
      .in[EmptyRequest]
      .run { _ =>
        timer.status.map(StatusResponse(_))
      }
  )

  // ── resources ─────────────────────────────────────────────────────

  override def resources(r: Unit, timer: PomodoroTimer) = List(
    McpResource.dynamic[IO](
      resourceUri = "pomodoro://status",
      resourceName = "Timer Status",
      resourceDescription = Some("Current pomodoro timer status (subscribable)"),
      resourceMimeType = Some("text/plain"),
      reader = () => timer.status
    ),

    McpResource.dynamic[IO](
      resourceUri = "pomodoro://history",
      resourceName = "Session History",
      resourceDescription = Some("Completed pomodoro session history"),
      resourceMimeType = Some("text/plain"),
      reader = () => timer.historyText
    )
  )

  // ── resource templates ────────────────────────────────────────────

  private val timerUriPrefix = "pomodoro://timers/"

  override def resourceTemplates(r: Unit, timer: PomodoroTimer) = List(
    new ResourceTemplateHandler[IO]:
      def uriTemplate = "pomodoro://timers/{name}"
      def name = "Timer by Name"
      override def description = Some("Look up a timer by its label")
      override def mimeType = Some("text/plain")
      def read(uri: String): Option[IO[ResourceContent]] =
        Option.when(uri.startsWith(timerUriPrefix)) {
          val timerName = uri.stripPrefix(timerUriPrefix)
          timer.statusForLabel(timerName).map {
            case Some(text) => ResourceContent.text(uri, text, mimeType)
            case None       => ResourceContent.text(uri, s"No timer found with label: $timerName", mimeType)
          }
        }
  )

  // ── prompts ───────────────────────────────────────────────────────

  override def prompts(r: Unit, timer: PomodoroTimer) = List(
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
    ),

    Prompt.dynamic[IO](
      promptName = "review_day",
      promptDescription = Some("Review completed pomodoro sessions for the day"),
      generator = { _ =>
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
  )
