package net.andimiller.mcp.http4s

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*

/**
 * Convenience trait for building MCP servers served over Streamable HTTP.
 *
 * Mirrors [[net.andimiller.mcp.stdio.StdioMcpResourceIOApp]] but uses
 * http4s Ember + SSE instead of stdio.
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

  /** Acquire managed resources (DB pools, HTTP clients, Refs, etc.) */
  def mkResources: Resource[IO, R]

  /** Tool handlers, given shared resources and a per-session notification sink */
  def tools(r: R, sink: NotificationSink[IO]): List[ToolHandler[IO]] = List.empty

  /** Resource handlers, given shared resources and a per-session notification sink */
  def resources(r: R, sink: NotificationSink[IO]): List[ResourceHandler[IO]] = List.empty

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
          .withPrompts(prompts(r, sink)*)
          .enableResourceSubscriptions
          .enableLogging
          .build
      }

      StreamableHttpTransport.routes[IO](serverFactory).flatMap { mcpRoutes =>
        val app = Router("/" -> mcpRoutes).orNotFound
        EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(app)
          .build
      }
    }.useForever
