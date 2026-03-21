package net.andimiller.mcp.stdio

import cats.effect.{IO, IOApp, Resource}
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.{ServerBuilder, Tool, ToolHandler, ResourceHandler, PromptHandler}

/**
 * Convenience trait for building MCP servers with managed lifecycle resources.
 *
 * Like [[StdioMcpIOApp]], but lets you acquire resources (DB pools, HTTP clients,
 * Refs, etc.) via a `cats.effect.Resource` before defining handlers.
 *
 * Example:
 * {{{
 * object MyServer extends StdioMcpResourceIOApp[MyServer.Resources]:
 *   case class Resources(db: DbPool, counter: Ref[IO, Int])
 *
 *   def serverName = "my-server"
 *   def serverVersion = "1.0.0"
 *
 *   def mkResources = Resource.eval(
 *     for
 *       db      <- DbPool.create
 *       counter <- Ref.of[IO, Int](0)
 *     yield Resources(db, counter)
 *   )
 *
 *   def tools(r: Resources) = List(
 *     tool("my_tool", "Description") { (req: MyRequest) =>
 *       r.counter.updateAndGet(_ + 1).map(MyResponse(_))
 *     }
 *   )
 * }}}
 */
trait StdioMcpResourceIOApp[R] extends IOApp.Simple:

  /** The name of this MCP server */
  def serverName: String

  /** The version of this MCP server */
  def serverVersion: String

  /** Acquire managed resources (DB pools, HTTP clients, Refs, etc.) */
  def mkResources: Resource[IO, R]

  /** List of tool handlers provided by this server, given the acquired resources */
  def tools(r: R): List[ToolHandler[IO]] = List.empty

  /** List of resource handlers provided by this server, given the acquired resources */
  def resources(r: R): List[ResourceHandler[IO]] = List.empty

  /** List of prompt handlers provided by this server, given the acquired resources */
  def prompts(r: R): List[PromptHandler[IO]] = List.empty

  /**
   * Helper method to build a tool handler.
   *
   * @param name The name of the tool
   * @param description A description of what the tool does
   * @param handler The function that handles tool requests
   */
  def tool[Req: JsonSchema: Decoder, Res: JsonSchema: Encoder.AsObject](
    name: String,
    description: String
  )(handler: Req => IO[Res]): ToolHandler[IO] =
    Tool.buildNamed[IO, Req, Res](name, description)(handler)

  /**
   * Builds and runs the MCP server via stdio transport.
   * Resources are acquired before the server starts and released on shutdown.
   */
  final def run: IO[Unit] =
    mkResources.use { r =>
      val server = ServerBuilder[IO](serverName, serverVersion)
        .withTools(tools(r)*)
        .withResources(resources(r)*)
        .withPrompts(prompts(r)*)
        .build
      StdioTransport.runServer[IO](server)
    }
