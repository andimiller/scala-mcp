package net.andimiller.mcp.http4s

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*
import org.http4s.server.{Server as HttpServer}

/**
 * Convenience trait for building MCP servers that need per-session stateful
 * resources (e.g. a timer, counter, or cache created from the notification sink).
 *
 * `R` is shared application state (created once), `S` is per-session state
 * (created for each client session from the shared state and notification sink).
 *
 * Example:
 * {{{
 * object MyServer extends HttpMcpStatefulResourceApp[Unit, MyTimer]:
 *   def serverName    = "my-server"
 *   def serverVersion = "1.0.0"
 *
 *   def mkResources = Resource.pure(())
 *
 *   def mkSessionResources(r: Unit, sink: NotificationSink[IO]) =
 *     MyTimer.create(sink)
 *
 *   def tools(r: Unit, s: MyTimer) = List(...)
 * }}}
 */
trait HttpMcpStatefulResourceApp[R, S] extends IOApp.Simple:

  /** The name of this MCP server */
  def serverName: String

  /** The version of this MCP server */
  def serverVersion: String

  /** Host to bind to (default: all interfaces) */
  def host: Host = host"0.0.0.0"

  /** Port to listen on (default: 8080) */
  def port: Port = port"8080"

  /** Whether to serve the MCP Explorer UI at /explorer (default: false) */
  def explorerEnabled: Boolean = false

  /** Whether to redirect / to /explorer/index.html (default: false, requires explorerEnabled) */
  def rootRedirectToExplorer: Boolean = false

  /** Transform the http4s server resource after it is built (e.g. to log the bound address) */
  def transformServer(server: Resource[IO, HttpServer]): Resource[IO, HttpServer] = server

  /** Acquire shared application resources (DB pools, HTTP clients, Refs, etc.) */
  def mkResources: Resource[IO, R]

  /** Create per-session state from shared resources and the session's notification sink */
  def mkSessionResources(r: R, sink: NotificationSink[IO]): IO[S]

  /** Tool handlers, given shared resources and per-session state */
  def tools(r: R, s: S): List[ToolHandler[IO]] = List.empty

  /** Resource handlers, given shared resources and per-session state */
  def resources(r: R, s: S): List[ResourceHandler[IO]] = List.empty

  /** Resource template handlers, given shared resources and per-session state */
  def resourceTemplates(r: R, s: S): List[ResourceTemplateHandler[IO]] = List.empty

  /** Prompt handlers, given shared resources and per-session state */
  def prompts(r: R, s: S): List[PromptHandler[IO]] = List.empty

  /**
   * Helper method to build a tool handler.
   */
  def tool[Req: JsonSchema: Decoder, Res: JsonSchema: Encoder.AsObject](
    name: String,
    description: String
  )(handler: Req => IO[Res]): ToolHandler[IO] =
    Tool.buildNamed[IO, Req, Res](name, description)(handler)

  final def run: IO[Unit] =
    mkResources.flatMap { r =>
      val serverFactory: NotificationSink[IO] => IO[Server[IO]] = { sink =>
        mkSessionResources(r, sink).flatMap { s =>
          ServerBuilder[IO](serverName, serverVersion)
            .withTools(tools(r, s)*)
            .withResources(resources(r, s)*)
            .withResourceTemplates(resourceTemplates(r, s)*)
            .withPrompts(prompts(r, s)*)
            .enableResourceSubscriptions
            .enableLogging
            .build
        }
      }

      HttpMcpRouting.serve(serverFactory, host, port, explorerEnabled, rootRedirectToExplorer, transformServer)
    }.useForever
