package net.andimiller.mcp.stdio

import cats.effect.{IO, IOApp, Resource}
import net.andimiller.mcp.core.server.{ServerBuilder, Tool, ToolBuilder, ToolHandler, ResourceHandler, PromptHandler}

trait StdioMcpResourceIOApp[R] extends IOApp.Simple:

  def serverName: String
  def serverVersion: String
  def mkResources: Resource[IO, R]
  def tools(r: R): List[ToolHandler[IO]] = List.empty
  def resources(r: R): List[ResourceHandler[IO]] = List.empty
  def prompts(r: R): List[PromptHandler[IO]] = List.empty

  def tool: ToolBuilder.Empty[IO] = Tool.builder[IO]

  final def run: IO[Unit] =
    mkResources.use { r =>
      val server = ServerBuilder[IO](serverName, serverVersion)
        .withTools(tools(r)*)
        .withResources(resources(r)*)
        .withPrompts(prompts(r)*)
        .build
      StdioTransport.runServer[IO](server)
    }