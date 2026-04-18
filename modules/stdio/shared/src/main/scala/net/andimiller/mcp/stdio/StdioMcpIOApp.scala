package net.andimiller.mcp.stdio

import cats.effect.{IO, IOApp}
import net.andimiller.mcp.core.server.{ServerBuilder, Tool, ToolBuilder, ToolHandler, ResourceHandler, PromptHandler}

trait StdioMcpIOApp extends IOApp.Simple:

  def serverName: String
  def serverVersion: String
  def tools: List[ToolHandler[IO]] = List.empty
  def resources: List[ResourceHandler[IO]] = List.empty
  def prompts: List[PromptHandler[IO]] = List.empty

  def tool: ToolBuilder.Empty[IO] = Tool.builder[IO]

  final def run: IO[Unit] =
    val server = ServerBuilder[IO](serverName, serverVersion)
      .withTools(tools*)
      .withResources(resources*)
      .withPrompts(prompts*)
      .build

    StdioTransport.runServer[IO](server)