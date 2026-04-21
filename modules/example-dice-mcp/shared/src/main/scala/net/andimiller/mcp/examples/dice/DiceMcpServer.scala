package net.andimiller.mcp.examples.dice

import cats.effect.{IO, IOApp, Ref, Resource}
import cats.effect.std.Random
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.protocol.{PromptArgument, PromptMessage}
import net.andimiller.mcp.core.schema.{JsonSchema, description, example}
import net.andimiller.mcp.core.server.{McpDsl, McpResource, Prompt, Server, ServerBuilder}
import net.andimiller.mcp.stdio.StdioTransport

object DiceMcpServer extends IOApp.Simple, McpDsl[IO]:

  case class DiceResources(
    rollHistory: Ref[IO, List[DiceRoller.RollResult]],
    random: Random[IO]
  )

  case class RollDiceRequest(
    @description("Dice notation (e.g., '1d6', '2d20 + 5', '3d4 - 2')")
    @example("1d6")
    @example("2d20 + 5")
    dice: String,
    @description("Number of times to roll (defaults to 1)")
    @example(1)
    @example(3)
    rolls: Option[Int] = None
  ) derives JsonSchema, Decoder

  case class RollResult(
    expression: String,
    min: Int,
    max: Int,
    result: Int,
    breakdown: String
  ) derives Encoder.AsObject, JsonSchema

  case class RollDiceResponse(
    rolls: List[RollResult]
  ) derives Encoder.AsObject, JsonSchema

  def server(r: DiceResources): IO[Server[IO]] =
    ServerBuilder[IO]("dice-mcp", "1.0.0")
      .withTool(
        tool.name("roll_dice")
          .description("Roll dice using standard notation (e.g., '1d6', '2d20 + 5', '3d4 - 2')")
          .in[RollDiceRequest]
          .run { request =>
            given Random[IO] = r.random
            val roller = DiceRoller[IO]
            val count = request.rolls.getOrElse(1)
            List.fill(count)(roller.rollDice(request.dice)).sequence.flatMap { results =>
              r.rollHistory.update(history => (results ++ history).take(100)) *>
              IO.pure(RollDiceResponse(
                rolls = results.map { result =>
                  RollResult(
                    expression = result.expression,
                    min = result.min,
                    max = result.max,
                    result = result.result,
                    breakdown = result.breakdown
                  )
                }
              ))
            }
          }
      )
      .withResource(
        McpResource.static[IO](
          resourceUri = "dice://rules/standard",
          resourceName = "Dice Notation Rules",
          resourceDescription = Some("Standard dice notation syntax and examples"),
          resourceMimeType = Some("text/markdown"),
          content = """|# Dice Notation
                       |
                       |## Basic Syntax
                       |
                       |Roll dice using the format `NdS` where:
                       |- **N** = number of dice to roll
                       |- **S** = number of sides per die
                       |
                       |## Modifiers
                       |
                       |Add or subtract a fixed value:
                       |- `NdS + M` adds M to the total
                       |- `NdS - M` subtracts M from the total
                       |
                       |## Examples
                       |
                       || Expression  | Meaning                          |
                       ||-------------|----------------------------------|
                       || `1d6`       | Roll one six-sided die           |
                       || `2d20`      | Roll two twenty-sided dice       |
                       || `3d8 + 5`   | Roll three eight-sided dice + 5  |
                       || `1d12 - 2`  | Roll one twelve-sided die - 2    |
                       || `4d6`       | Roll four six-sided dice         |
                       |""".stripMargin
        )
      )
      .withResource(
        McpResource.dynamic[IO](
          resourceUri = "dice://history",
          resourceName = "Roll History",
          resourceDescription = Some("Recent dice roll results"),
          resourceMimeType = Some("text/plain"),
          reader = () => r.rollHistory.get.map { history =>
            if history.isEmpty then "No rolls yet."
            else
              history.zipWithIndex.map { (roll, i) =>
                s"${i + 1}. ${roll.expression} => ${roll.result} (${roll.breakdown})"
              }.mkString("\n")
          }
        )
      )
      .withPrompt(
        Prompt.static[IO](
          promptName = "explain_notation",
          promptDescription = Some("Explain dice notation syntax"),
          messages = List(
            PromptMessage.user("Explain the dice notation syntax supported by this dice roller."),
            PromptMessage.assistant(
              """|This dice roller uses standard tabletop RPG dice notation:
                 |
                 |**Basic rolls:** `NdS` where N is the number of dice and S is the number of sides.
                 |For example, `2d6` rolls two six-sided dice and sums the results.
                 |
                 |**Modifiers:** You can add `+ M` or `- M` after the roll to add or subtract a fixed value.
                 |For example, `1d20 + 5` rolls a twenty-sided die and adds 5.
                 |
                 |**Common dice:** d4, d6, d8, d10, d12, d20, and d100 are standard, but any number of sides works.
                 |""".stripMargin
            )
          )
        )
      )
      .build

  def run: IO[Unit] =
    Resource.eval(
      for
        history <- Ref.of[IO, List[DiceRoller.RollResult]](Nil)
        random  <- Random.scalaUtilRandom[IO]
      yield DiceResources(history, random)
    ).use { r =>
      server(r).flatMap(StdioTransport.run[IO])
    }
