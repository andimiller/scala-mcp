package net.andimiller.mcp.http4s

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.semigroupk.*
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.dsl.io.*
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*

/**
 * Convenience trait for building MCP servers served over Streamable HTTP.
 *
 * Example:
 * {{{
 * object MyServer extends HttpMcpApp[MyServer.Res]:
 *   case class Res(counter: Ref[IO, Int])
 *
 *   def serverName    = "my-server"
 *   def serverVersion = "1.0.0"
 *
 *   def mkResources = Resource.eval(Ref.of[IO, Int](0).map(Res(_)))
 *
 *   def tools(r: Res, sink: NotificationSink[IO]) = List(...)
 * }}}
 */
trait HttpMcpApp[R] extends IOApp.Simple:

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

  /** Acquire managed resources (DB pools, HTTP clients, Refs, etc.) */
  def mkResources: Resource[IO, R]

  /** Tool handlers, given shared resources and a per-session notification sink */
  def tools(r: R, sink: NotificationSink[IO]): List[ToolHandler[IO]] = List.empty

  /** Resource handlers, given shared resources and a per-session notification sink */
  def resources(r: R, sink: NotificationSink[IO]): List[ResourceHandler[IO]] = List.empty

  /** Resource template handlers, given shared resources and a per-session notification sink */
  def resourceTemplates(r: R, sink: NotificationSink[IO]): List[ResourceTemplateHandler[IO]] = List.empty

  /** Prompt handlers, given shared resources and a per-session notification sink */
  def prompts(r: R, sink: NotificationSink[IO]): List[PromptHandler[IO]] = List.empty

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
        ServerBuilder[IO](serverName, serverVersion)
          .withTools(tools(r, sink)*)
          .withResources(resources(r, sink)*)
          .withResourceTemplates(resourceTemplates(r, sink)*)
          .withPrompts(prompts(r, sink)*)
          .enableResourceSubscriptions
          .enableLogging
          .build
      }

      HttpMcpRouting.serve(serverFactory, host, port, explorerEnabled, rootRedirectToExplorer)
    }.useForever
