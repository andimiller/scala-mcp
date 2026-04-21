package net.andimiller.mcp.examples.pomodoro

import cats.effect.IO
import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.golden.McpGoldenSuite
import net.andimiller.mcp.http4s.McpHttp

class PomodoroMcpServerGoldenSuite extends McpGoldenSuite:
  override def goldenFileName = "pomodoro-mcp.json"

  def server: IO[Server[IO]] =
    PomodoroMcpServer
      .configure(McpHttp.streaming[IO].name("pomodoro-mcp").version("1.0.0"))
      .buildServer
