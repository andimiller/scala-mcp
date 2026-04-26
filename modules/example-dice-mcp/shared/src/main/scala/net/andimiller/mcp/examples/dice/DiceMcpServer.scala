package net.andimiller.mcp.examples.dice

import cats.effect.{IO, IOApp, Ref, Resource}
import cats.effect.std.Random
import cats.syntax.all.*
import io.circe.{Codec, Decoder, Encoder}
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.{ElicitResult, ElicitationError, PromptArgument, PromptMessage, ToolResult}
import net.andimiller.mcp.core.schema.{JsonSchema, description, example}
import net.andimiller.mcp.core.server.{ElicitationClient, McpDsl, McpResource, Prompt, Server, ServerBuilder}
import net.andimiller.mcp.stdio.StdioTransport
import sttp.apispec.Schema

object DiceMcpServer extends IOApp.Simple, McpDsl[IO]:

  case class DiceResources(
    rollHistory: Ref[IO, List[DiceRoller.RollResult]],
    random: Random[IO],
    elicitation: ElicitationClient[IO]
  )

  case class RollCustomRequest() derives JsonSchema, Decoder

  enum DiceFace(val sides: Int):
    case D2   extends DiceFace(2)
    case D4   extends DiceFace(4)
    case D6   extends DiceFace(6)
    case D12  extends DiceFace(12)
    case D20  extends DiceFace(20)
    case D100 extends DiceFace(100)

  object DiceFace:
    val all: List[DiceFace] = List(D2, D4, D6, D12, D20, D100)
    private def label(d: DiceFace): String = s"d${d.sides}"

    given Codec[DiceFace] = Codec.from(
      Decoder[String].emap {
        case "d2"   => Right(D2)
        case "d4"   => Right(D4)
        case "d6"   => Right(D6)
        case "d12"  => Right(D12)
        case "d20"  => Right(D20)
        case "d100" => Right(D100)
        case other  => Left(s"Unknown dice face: $other")
      },
      Encoder[String].contramap(label)
    )

    given JsonSchema[DiceFace] with
      def schema: Schema = JsonSchema.string.withEnum(all.map(label)*)

  case class DiceChoice(
    @description("Which dice to roll")
    face: DiceFace,
    @description("How many to roll")
    @example(1)
    @example(3)
    count: Int
  ) derives JsonSchema, Codec.AsObject

  case class InteractiveRollResult(
    @description("The full dice expression that was rolled")
    expression: String,
    @description("Numeric total of the roll")
    result: Int,
    @description("Per-die breakdown of the roll")
    breakdown: String
  ) derives JsonSchema, Encoder.AsObject

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
      .withTool(
        tool.name("roll_interactive")
          .description("Build a dice expression interactively: choose dice + counts, then roll the lot")
          .in[RollCustomRequest]
          .runResult[InteractiveRollResult] { _ =>
            given Random[IO] = r.random
            val roller = DiceRoller[IO]

            def elicitChoice(round: Int): IO[Either[ElicitationError, ElicitResult[DiceChoice]]] =
              r.elicitation.requestForm[DiceChoice](
                message =
                  if round == 0 then "Choose a dice to roll"
                  else "Choose another dice — or decline to stop"
              )

            def collect(round: Int, acc: List[DiceChoice]): IO[Either[ToolResult[InteractiveRollResult], List[DiceChoice]]] =
              elicitChoice(round).flatMap {
                case Left(ElicitationError.CapabilityMissing) =>
                  IO.pure(Left(ToolResult.Error("This client does not support form elicitation")))
                case Left(err) =>
                  IO.pure(Left(ToolResult.Error(s"Elicitation failed: $err")))
                case Right(ElicitResult.Accept(choice)) if choice.count <= 0 =>
                  IO.pure(Left(ToolResult.Error(s"count must be positive (got ${choice.count})")))
                case Right(ElicitResult.Accept(choice)) =>
                  collect(round + 1, acc :+ choice)
                case Right(ElicitResult.Decline) | Right(ElicitResult.Cancel) =>
                  IO.pure(Right(acc))
              }

            collect(0, Nil).flatMap {
              case Left(error)    => IO.pure(error)
              case Right(Nil)     => IO.pure(ToolResult.Text("No dice were chosen"))
              case Right(choices) =>
                val expr = choices.map(c => s"${c.count}d${c.face.sides}").mkString(" + ")
                roller.rollDice(expr).flatMap { result =>
                  r.rollHistory.update(h => (result :: h).take(100)).as(
                    ToolResult.Success(InteractiveRollResult(
                      expression = result.expression,
                      result = result.result,
                      breakdown = result.breakdown
                    ))
                  )
                }
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
    StdioTransport.run[IO] { ctx =>
      for
        history <- Ref.of[IO, List[DiceRoller.RollResult]](Nil)
        random  <- Random.scalaUtilRandom[IO]
        srv     <- server(DiceResources(history, random, ctx.elicitation))
      yield srv
    }
