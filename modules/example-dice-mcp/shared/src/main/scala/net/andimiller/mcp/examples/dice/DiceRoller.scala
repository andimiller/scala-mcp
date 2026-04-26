package net.andimiller.mcp.examples.dice

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

      case DiceParser.Constant(n) =>
        MonadThrow[F].pure((n, n.toString))

      case DiceParser.Add(left, right) =>
        (roll(left), roll(right)).tupled.map { case ((l, lb), (r, rb)) =>
          val total = l + r
          (total, s"$lb + $rb = $total")
        }

      case DiceParser.Subtract(left, right) =>
        (roll(left), roll(right)).tupled.map { case ((l, lb), (r, rb)) =>
          val total = l - r
          (total, s"$lb - $rb = $total")
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
      case DiceParser.Roll(dice)        => dice.count // minimum is rolling all 1s
      case DiceParser.Constant(n)       => n
      case DiceParser.Add(left, right)  => calculateMin(left) + calculateMin(right)
      // worst case for `a - b` is min(a) − max(b)
      case DiceParser.Subtract(left, right) => calculateMin(left) - calculateMax(right)

  /**
   * Calculate the maximum possible value for a dice expression
   */
  def calculateMax(expr: DiceParser.DiceExpr): Int =
    expr match
      case DiceParser.Roll(dice)        => dice.count * dice.sides
      case DiceParser.Constant(n)       => n
      case DiceParser.Add(left, right)  => calculateMax(left) + calculateMax(right)
      // best case for `a - b` is max(a) − min(b)
      case DiceParser.Subtract(left, right) => calculateMax(left) - calculateMin(right)
