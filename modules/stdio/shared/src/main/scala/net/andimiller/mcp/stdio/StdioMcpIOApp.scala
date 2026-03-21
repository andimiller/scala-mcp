package net.andimiller.mcp.stdio

import cats.effect.{IO, IOApp}
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.{ServerBuilder, Tool, ToolHandler, ResourceHandler, PromptHandler}

/**
 * Convenience trait for building MCP servers that communicate via stdio.
 *
 * Extend this trait and override serverName, serverVersion, and optionally
 * tools, resources, and prompts to create a complete MCP server.
 *
 * Example:
 * {{{
 * object MyServer extends StdioMcpIOApp:
 *   def serverName = "my-server"
 *   def serverVersion = "1.0.0"
 *
 *   def tools = List(
 *     tool("my_tool", "Description") { (req: MyRequest) =>
 *       IO.pure(MyResponse(...))
 *     }
 *   )
 * }}}
 */
trait StdioMcpIOApp extends IOApp.Simple:

  /** The name of this MCP server */
  def serverName: String

  /** The version of this MCP server */
  def serverVersion: String

  /** List of tool handlers provided by this server */
  def tools: List[ToolHandler[IO]] = List.empty

  /** List of resource handlers provided by this server */
  def resources: List[ResourceHandler[IO]] = List.empty

  /** List of prompt handlers provided by this server */
  def prompts: List[PromptHandler[IO]] = List.empty

  /**
   * Helper method to build a tool handler.
   *
   * @param name The name of the tool
   * @param description A description of what the tool does
   * @param handler The function that handles tool requests
   */
  def tool[Req: JsonSchema: Decoder, Res: JsonSchema: Encoder](
    name: String,
    description: String
  )(handler: Req => IO[Res]): ToolHandler[IO] =
    Tool.buildNamed[IO, Req, Res](name, description)(handler)

  /**
   * Builds and runs the MCP server via stdio transport.
   * This method is called automatically when the app starts.
   */
  final def run: IO[Unit] =
    val server = ServerBuilder[IO](serverName, serverVersion)
      .withTools(tools*)
      .withResources(resources*)
      .withPrompts(prompts*)
      .build

    StdioTransport.runServer[IO](server)
