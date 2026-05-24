package net.andimiller.mcp.examples.apps

import java.time.Instant

import cats.effect.*
import cats.syntax.all.*

import com.comcast.ip4s.*

import io.circe.Decoder
import io.circe.Encoder

import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.MediaType

import net.andimiller.mcp.apps.*
import net.andimiller.mcp.apps.syntax.*
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.schema.description
import net.andimiller.mcp.core.server.ServerBuilder
import net.andimiller.mcp.http4s.McpHttp

case class GetTimeRequest() derives JsonSchema, Decoder

case class GetTimeResponse(
    @description("ISO-8601 timestamp in UTC")
    isoTime: String
) derives Encoder.AsObject,
      JsonSchema

object ClockMcpServer extends IOApp.Simple:

  private def loadIframeJs: IO[String] =
    IO.blocking {
      val stream = getClass.getResourceAsStream("/clock-app/main.js")
      if stream == null then
        throw new RuntimeException(
          "Missing /clock-app/main.js — run `sbt buildClockApp` to build the iframe first"
        )
      try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()
    }

  private def buildHtml(iframeJs: String): String =
    s"""<!doctype html>
       |<html>
       |  <head>
       |    <meta charset="utf-8"/>
       |    <title>Clock</title>
       |  </head>
       |  <body>
       |    <div id="main"></div>
       |    <script>$iframeJs</script>
       |  </body>
       |</html>""".stripMargin

  private def buildServer(iframeHtml: String) =
    ServerBuilder[IO]("clock-mcp", "0.1.0")
      .withTitle("Clock MCP")
      .withDescription("Demonstrates the MCP Apps extension — surfaces a `get_time` tool whose UI is a Tyrian iframe.")
      .withAppsExtension
      .withTool(
        net.andimiller.mcp.core.server.Tool.builder
          .name("get_time")
          .description("Get the current UTC time as an ISO-8601 string. The host renders the result in the Tyrian iframe.")
          .asApp("ui://clock")
          .in[GetTimeRequest]
          .out[GetTimeResponse]
          .run(_ => IO(GetTimeResponse(Instant.now().toString)))
      )
      .withResource(
        AppResource.html[IO](
          uri = "ui://clock",
          name = "Clock UI",
          title = Some("Clock"),
          description = Some("Tyrian-rendered clock app that displays the result of get_time."),
          html = iframeHtml,
          ui = ResourceUiMetadata(prefersBorder = Some(true))
        )
      )
      .build

  final def run: IO[Unit] =
    loadIframeJs.flatMap { js =>
      val html = buildHtml(js)
      buildServer(html).flatMap { mcpServer =>
        val mcpRoutes   = McpHttp.routes[IO](IO.pure(mcpServer))
        val healthRoute = HttpRoutes.of[IO] { case GET -> Root / "health" =>
          Ok("ok").map(_.withContentType(`Content-Type`(MediaType.text.plain)))
        }
        // Serve the iframe HTML directly so it can be opened in a browser for offline debugging without the host's
        // sandbox / postMessage plumbing. Without a real host the Tyrian app's bridge calls are sent to `window.parent`
        // which is the window itself (no-op) but the UI still renders.
        val previewRoute = HttpRoutes.of[IO] { case GET -> Root / "preview" =>
          Ok(html).map(_.withContentType(`Content-Type`(MediaType.text.html)))
        }
        val app          = Router("/" -> (healthRoute <+> previewRoute <+> mcpRoutes)).orNotFound
        EmberServerBuilder
          .default[IO]
          .withHost(host"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(app)
          .build
          .use(_ =>
            IO.println("clock-mcp listening on http://localhost:8080/mcp")
              *> IO.println("iframe preview at http://localhost:8080/preview")
              *> IO.never
          )
      }
    }
