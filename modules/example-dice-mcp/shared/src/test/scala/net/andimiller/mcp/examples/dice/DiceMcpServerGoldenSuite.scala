package net.andimiller.mcp.examples.dice

import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Random

import net.andimiller.mcp.core.server.ElicitationClient
import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.core.server.ServerRequester
import net.andimiller.mcp.golden.McpGoldenSuite

class DiceMcpServerGoldenSuite extends McpGoldenSuite:

  override def goldenFileName = "dice-mcp.json"

  def server: IO[Server[IO]] =
    for
      history   <- Ref.of[IO, List[DiceRoller.RollResult]](Nil)
      random    <- Random.scalaUtilRandom[IO]
      requester <- ServerRequester.noop[IO]
      elic       = ElicitationClient.fromRequester(requester)
      s         <- DiceMcpServer.server(DiceMcpServer.DiceResources(history, random, elic))
    yield s
