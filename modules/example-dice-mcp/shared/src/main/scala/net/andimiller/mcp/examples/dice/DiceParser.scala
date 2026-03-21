package net.andimiller.mcp.examples.dice

import cats.parse.{Parser as P, Parser0 as P0, Numbers}
import cats.parse.Rfc5234.{wsp, digit}

/**
 * Dice notation parser for expressions like "1d6", "2d20 + 5", "3d4 - 2"
 */
object DiceParser:

  case class DiceRoll(count: Int, sides: Int)

  sealed trait DiceExpr
  case class Roll(dice: DiceRoll) extends DiceExpr
  case class Add(left: DiceExpr, right: Int) extends DiceExpr
  case class Subtract(left: DiceExpr, right: Int) extends DiceExpr

  // Parse a positive integer
  private val integer: P[Int] = Numbers.nonNegativeIntString.map(_.toInt)

  // Parse "XdY" where X is the number of dice and Y is the number of sides
  private val diceRoll: P[DiceRoll] =
    ((integer <* P.char('d')) ~ integer).map { case (count, sides) =>
      DiceRoll(count, sides)
    }

  // Parse optional whitespace
  private val spaces: P0[Unit] = wsp.rep0.void

  // Parse the base dice roll
  private val baseDice: P[DiceExpr] = diceRoll.map(Roll.apply)

  // Parse modifier: "+ N" or "- N", with optional surrounding whitespace
  private val modifier: P[DiceExpr => DiceExpr] =
    ((spaces.with1 *> P.charIn('+', '-') <* spaces) ~ integer).map {
      case ('+', n) => (expr: DiceExpr) => Add(expr, n)
      case (_, n)   => (expr: DiceExpr) => Subtract(expr, n)
    }

  // Parse a complete dice expression
  private val diceExpr: P[DiceExpr] =
    (baseDice ~ modifier.backtrack.?).map {
      case (expr, Some(mod)) => mod(expr)
      case (expr, None) => expr
    }

  /**
   * Parse a dice notation string into a DiceExpr
   */
  def parse(input: String): Either[P.Error, DiceExpr] =
    (spaces.with1 *> diceExpr <* spaces).parseAll(input)
