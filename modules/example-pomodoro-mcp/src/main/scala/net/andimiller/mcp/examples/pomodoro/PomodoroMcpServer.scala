package net.andimiller.mcp.examples.pomodoro

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{SecureRandom, UUIDGen}
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder, Json}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import net.andimiller.mcp.core.protocol.{PromptArgument, PromptMessage, ResourceContent}
import net.andimiller.mcp.core.schema.{JsonSchema, description}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.StreamableHttpTransport

/**
 * Pomodoro MCP server demonstrating:
 *  - Dynamic resources with subscription notifications
 *  - Server-initiated notifications (resource updates, logging)
 *  - Dynamic prompts with arguments
 *  - Streamable HTTP transport
 */
object PomodoroMcpServer extends cats.effect.IOApp.Simple:

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

  // ── server construction ───────────────────────────────────────────

  /** Build a Server[IO] with a per-session timer backed by the given sink. */
  private def mkServer(sink: NotificationSink[IO]): IO[Server[IO]] =
    for
      timer <- PomodoroTimer.create(sink)
      server <- ServerBuilder[IO]("pomodoro-mcp", "1.0.0")
        .withTools(mkTools(timer)*)
        .withResources(mkResources(timer)*)
        .withResourceTemplates(mkResourceTemplates(timer)*)
        .withPrompts(mkPrompts(timer)*)
        .enableResourceSubscriptions
        .enableLogging
        .build
    yield server

  // ── tools ─────────────────────────────────────────────────────────

  private def mkTools(timer: PomodoroTimer): List[ToolHandler[IO]] = List(
    Tool.buildNamed[IO, StartTimerRequest, MessageResponse](
      "start_timer",
      "Start a new pomodoro timer"
    ) { req =>
      timer.start(req.duration_minutes, req.label).map(MessageResponse(_))
    },

    Tool.buildNamed[IO, EmptyRequest, MessageResponse](
      "pause_timer",
      "Pause the running pomodoro timer"
    ) { _ =>
      timer.pause().map(MessageResponse(_))
    },

    Tool.buildNamed[IO, EmptyRequest, MessageResponse](
      "resume_timer",
      "Resume a paused pomodoro timer"
    ) { _ =>
      timer.resume().map(MessageResponse(_))
    },

    Tool.buildNamed[IO, EmptyRequest, MessageResponse](
      "stop_timer",
      "Stop/cancel the current pomodoro timer"
    ) { _ =>
      timer.stop().map(MessageResponse(_))
    },

    Tool.buildNamed[IO, EmptyRequest, StatusResponse](
      "get_status",
      "Get the current pomodoro timer status"
    ) { _ =>
      timer.status.map(StatusResponse(_))
    }
  )

  // ── resources ─────────────────────────────────────────────────────

  private def mkResources(timer: PomodoroTimer): List[ResourceHandler[IO]] = List(
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

  // ── resource templates ─────────────────────────────────────────────

  private val timerUriPrefix = "pomodoro://timers/"

  private def mkResourceTemplates(timer: PomodoroTimer): List[ResourceTemplateHandler[IO]] = List(
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

  // ── prompts (with arguments — unlike dice's static prompts) ───────

  private def mkPrompts(timer: PomodoroTimer): List[PromptHandler[IO]] = List(
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

  // ── main ──────────────────────────────────────────────────────────

  def run: IO[Unit] =
    SecureRandom.javaSecuritySecureRandom[IO].flatMap { sr =>
      given SecureRandom[IO] = sr

      StreamableHttpTransport.routes[IO](mkServer).flatMap { mcpRoutes =>
        val app = Router("/" -> mcpRoutes).orNotFound
        EmberServerBuilder
          .default[IO]
          .withHost(host"0.0.0.0")
          .withPort(port"25000")
          .withHttpApp(app)
          .build
      }.useForever
    }
