package net.andimiller.mcp.http4s

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.semigroupk.*
import com.comcast.ip4s.*
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.dsl.io.*
import net.andimiller.mcp.core.server.*
import org.http4s.server.{Server as HttpServer}

trait HttpMcpApp[R] extends IOApp.Simple:

  def serverName: String
  def serverVersion: String
  def host: Host = host"0.0.0.0"
  def port: Port = port"8080"
  def explorerEnabled: Boolean = false
  def rootRedirectToExplorer: Boolean = false
  def transformServer(server: Resource[IO, HttpServer]): Resource[IO, HttpServer] = server
  def mkResources: Resource[IO, R]
  def tools(r: R, sink: NotificationSink[IO]): List[ToolHandler[IO]] = List.empty
  def resources(r: R, sink: NotificationSink[IO]): List[ResourceHandler[IO]] = List.empty
  def resourceTemplates(r: R, sink: NotificationSink[IO]): List[ResourceTemplateHandler[IO]] = List.empty
  def prompts(r: R, sink: NotificationSink[IO]): List[PromptHandler[IO]] = List.empty

  def tool: ToolBuilder.Empty[IO] = Tool.builder[IO]

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

      HttpMcpRouting.serve(serverFactory, host, port, explorerEnabled, rootRedirectToExplorer, transformServer)
    }.useForever