package net.andimiller.mcp.examples.dice

import cats.effect.{IO, Ref}
import cats.effect.std.Random
import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.golden.McpGoldenSuite

class DiceMcpServerGoldenSuite extends McpGoldenSuite:
  override def goldenFileName = "dice-mcp.json"

  def server: IO[Server[IO]] =
    for
      history <- Ref.of[IO, List[DiceRoller.RollResult]](Nil)
      random  <- Random.scalaUtilRandom[IO]
      s       <- DiceMcpServer.server(DiceMcpServer.DiceResources(history, random))
    yield s
