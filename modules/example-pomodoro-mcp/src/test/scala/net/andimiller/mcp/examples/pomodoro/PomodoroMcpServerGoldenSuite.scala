package net.andimiller.mcp.examples.pomodoro

import cats.effect.IO

import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.golden.McpGoldenSuite
import net.andimiller.mcp.http4s.McpHttp

import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

class PomodoroMcpServerGoldenSuite extends McpGoldenSuite:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  override def goldenFileName = "pomodoro-mcp.json"

  def server: IO[Server[IO]] =
    PomodoroMcpServer
      .configure(McpHttp.streaming[IO].name("pomodoro-mcp").version("1.0.0"))
      .buildServer
