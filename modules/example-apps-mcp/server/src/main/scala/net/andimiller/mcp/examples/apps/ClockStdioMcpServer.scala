package net.andimiller.mcp.examples.apps

import java.time.Instant

import cats.effect.*

import net.andimiller.mcp.apps.*
import net.andimiller.mcp.apps.syntax.*
import net.andimiller.mcp.core.server.ServerBuilder
import net.andimiller.mcp.stdio.StdioTransport

object ClockStdioMcpServer extends IOApp.Simple:

  private def loadIframeJs: IO[String] =
    IO.blocking {
      val stream = getClass.getResourceAsStream("/clock-app/main.js")
      if stream == null then
        throw new RuntimeException(
          "Missing /clock-app/main.js — run `sbt buildClockApp` to build the iframe before assembly"
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

  final def run: IO[Unit] =
    loadIframeJs.flatMap { js =>
      val html = buildHtml(js)
      val serverIO = ServerBuilder[IO]("clock-mcp", "0.1.0")
        .withTitle("Clock MCP (stdio)")
        .withDescription(
          "Demonstrates the MCP Apps extension — surfaces a `get_time` tool whose UI is a Tyrian iframe."
        )
        .withAppsExtension
        .withTool(
          net.andimiller.mcp.core.server.Tool.builder
            .name("get_time")
            .description(
              "Get the current UTC time as an ISO-8601 string. The host renders the result in the Tyrian iframe."
            )
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
            html = html,
            ui = ResourceUiMetadata(prefersBorder = Some(true))
          )
        )
        .build
      StdioTransport.runServer[IO](serverIO)
    }
