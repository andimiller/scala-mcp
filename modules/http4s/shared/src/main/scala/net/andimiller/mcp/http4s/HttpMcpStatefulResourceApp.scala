package net.andimiller.mcp.http4s

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.*
import net.andimiller.mcp.core.server.*
import org.http4s.server.{Server as HttpServer}

trait HttpMcpStatefulResourceApp[R, S] extends IOApp.Simple:

  def serverName: String
  def serverVersion: String
  def host: Host = host"0.0.0.0"
  def port: Port = port"8080"
  def explorerEnabled: Boolean = false
  def rootRedirectToExplorer: Boolean = false
  def transformServer(server: Resource[IO, HttpServer]): Resource[IO, HttpServer] = server
  def mkResources: Resource[IO, R]
  def mkSessionResources(r: R, sink: NotificationSink[IO]): IO[S]
  def tools(r: R, s: S): List[ToolHandler[IO]] = List.empty
  def resources(r: R, s: S): List[ResourceHandler[IO]] = List.empty
  def resourceTemplates(r: R, s: S): List[ResourceTemplateHandler[IO]] = List.empty
  def prompts(r: R, s: S): List[PromptHandler[IO]] = List.empty

  def tool: ToolBuilder.Empty[IO] = Tool.builder[IO]

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