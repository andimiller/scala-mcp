package mcp.examples.dice

import cats.effect.{IO, Ref}
import cats.effect.std.Random
import io.circe.{Decoder, Encoder}
import mcp.core.protocol.{PromptArgument, PromptMessage}
import mcp.core.schema.JsonSchema
import mcp.core.server.{Prompt, Resource, PromptHandler, ResourceHandler}
import mcp.stdio.StdioMcpIOApp

object DiceMcpServer extends StdioMcpIOApp:

  case class RollDiceRequest(
    dice: String
  ) derives JsonSchema, Decoder

  case class RollDiceResponse(
    expression: String,
    min: Int,
    max: Int,
    result: Int,
    breakdown: String
  ) derives Encoder, JsonSchema

  // Shared state: roll history
  private val rollHistory: Ref[IO, List[DiceRoller.RollResult]] = Ref.unsafe(List.empty)

  // Server configuration
  override def serverName = "dice-mcp"
  override def serverVersion = "1.0.0"

  // Define the tools provided by this server
  override def tools = List(
    tool("roll_dice", "Roll dice using standard notation (e.g., '1d6', '2d20 + 5', '3d4 - 2')") {
      (request: RollDiceRequest) =>
        Random.scalaUtilRandom[IO].flatMap { random =>
          given Random[IO] = random
          val roller = DiceRoller[IO]
          roller.rollDice(request.dice).flatMap { result =>
            rollHistory.update(history => (result :: history).take(100)) *>
            IO.pure(RollDiceResponse(
              expression = result.expression,
              min = result.min,
              max = result.max,
              result = result.result,
              breakdown = result.breakdown
            ))
          }
        }
    }
  )

  // Define the resources provided by this server
  override def resources: List[ResourceHandler[IO]] = List(
    Resource.static[IO](
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
    ),

    Resource.dynamic[IO](
      resourceUri = "dice://history",
      resourceName = "Roll History",
      resourceDescription = Some("Recent dice roll results"),
      resourceMimeType = Some("text/plain"),
      reader = () => rollHistory.get.map { history =>
        if history.isEmpty then "No rolls yet."
        else
          history.zipWithIndex.map { (roll, i) =>
            s"${i + 1}. ${roll.expression} => ${roll.result} (${roll.breakdown})"
          }.mkString("\n")
      }
    )
  )

  // Define the prompts provided by this server
  override def prompts: List[PromptHandler[IO]] = List(
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
