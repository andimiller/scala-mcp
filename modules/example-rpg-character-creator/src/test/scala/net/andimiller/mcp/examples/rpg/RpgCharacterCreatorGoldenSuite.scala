package net.andimiller.mcp.examples.rpg

import cats.effect.IO
import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.golden.McpGoldenSuite
import net.andimiller.mcp.http4s.McpHttp

class RpgCharacterCreatorGoldenSuite extends McpGoldenSuite:

  override def goldenFileName = "rpg-character-creator-mcp.json"

  def server: IO[Server[IO]] =
    RpgCharacterCreatorMcpServer
      .configure(McpHttp.streaming[IO].name("rpg-character-creator-mcp").version("1.0.0"))
      .buildServer
