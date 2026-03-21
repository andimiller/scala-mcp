package mcp.examples.dice

import cats.MonadThrow
import cats.effect.std.Random
import cats.syntax.all.*

/**
 * Evaluates dice expressions and computes min/max/result
 */
class DiceRoller[F[_]: MonadThrow: Random]:

  private val random = Random[F]

  /**
   * Roll the dice and calculate the result
   */
  def roll(expr: DiceParser.DiceExpr): F[(Int, String)] =
    expr match
      case DiceParser.Roll(dice) =>
        List.fill(dice.count)(random.nextIntBounded(dice.sides).map(_ + 1))
          .sequence
          .map { rolls =>
            val total = rolls.sum
            val breakdown = s"${dice.count}d${dice.sides} → [${rolls.mkString(", ")}] = $total"
            (total, breakdown)
          }

      case DiceParser.Add(left, n) =>
        roll(left).map { (leftResult, leftBreakdown) =>
          val total = leftResult + n
          (total, s"$leftBreakdown + $n = $total")
        }

      case DiceParser.Subtract(left, n) =>
        roll(left).map { (leftResult, leftBreakdown) =>
          val total = leftResult - n
          (total, s"$leftBreakdown - $n = $total")
        }

  /**
   * Parse and roll a dice expression
   */
  def rollDice(expression: String): F[DiceRoller.RollResult] =
    DiceParser.parse(expression) match
      case Left(error) =>
        MonadThrow[F].raiseError(new Exception(s"Parse error: ${error.toString}"))
      case Right(expr) =>
        val min = DiceRoller.calculateMin(expr)
        val max = DiceRoller.calculateMax(expr)
        roll(expr).map { (result, breakdown) =>
          DiceRoller.RollResult(
            expression = expression,
            min = min,
            max = max,
            result = result,
            breakdown = breakdown
          )
        }

object DiceRoller:

  case class RollResult(
    expression: String,
    min: Int,
    max: Int,
    result: Int,
    breakdown: String
  )

  /**
   * Calculate the minimum possible value for a dice expression
   */
  def calculateMin(expr: DiceParser.DiceExpr): Int =
    expr match
      case DiceParser.Roll(dice) =>
        dice.count // minimum is rolling all 1s
      case DiceParser.Add(left, n) =>
        calculateMin(left) + n
      case DiceParser.Subtract(left, n) =>
        calculateMin(left) - n

  /**
   * Calculate the maximum possible value for a dice expression
   */
  def calculateMax(expr: DiceParser.DiceExpr): Int =
    expr match
      case DiceParser.Roll(dice) =>
        dice.count * dice.sides // maximum is rolling max on all dice
      case DiceParser.Add(left, n) =>
        calculateMax(left) + n
      case DiceParser.Subtract(left, n) =>
        calculateMax(left) - n
