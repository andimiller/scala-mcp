package net.andimiller.mcp.examples.dice

import cats.parse.Parser as P
import cats.parse.Parser0 as P0
import cats.parse.Numbers
import cats.parse.Rfc5234.wsp

/** Dice notation parser for expressions like "1d6", "2d20 + 5", "3d4 - 2", "2d6 + 1d2 - 1".
  *
  * An expression is a chain of *terms* (a dice roll or an integer constant) joined by `+` or `-`, evaluated
  * left-to-right.
  */
object DiceParser:

  case class DiceRoll(count: Int, sides: Int)

  sealed trait DiceExpr

  case class Roll(dice: DiceRoll) extends DiceExpr

  case class Constant(value: Int) extends DiceExpr

  case class Add(left: DiceExpr, right: DiceExpr) extends DiceExpr

  case class Subtract(left: DiceExpr, right: DiceExpr) extends DiceExpr

  // Parse a positive integer
  private val integer: P[Int] = Numbers.nonNegativeIntString.map(_.toInt)

  // Parse "XdY" where X is the number of dice and Y is the number of sides
  private val diceRoll: P[DiceRoll] =
    ((integer <* P.char('d')) ~ integer).map { case (count, sides) =>
      DiceRoll(count, sides)
    }

  // Parse optional whitespace
  private val spaces: P0[Unit] = wsp.rep0.void

  // A single term: a dice roll (preferred — backtracked so a bare integer also matches)
  // or a constant integer.
  private val term: P[DiceExpr] =
    diceRoll.map(Roll.apply).backtrack | integer.map(Constant.apply)

  // An operator (`+` or `-`) followed by another term, with optional surrounding whitespace.
  private val opAndTerm: P[(Char, DiceExpr)] =
    (spaces.with1 *> P.charIn('+', '-') <* spaces) ~ term

  // A chain of terms joined by + / -, evaluated left-associatively.
  private val diceExpr: P[DiceExpr] =
    (term ~ opAndTerm.backtrack.rep0).map { (head, tail) =>
      tail.foldLeft(head) {
        case (acc, ('+', rhs)) => Add(acc, rhs)
        case (acc, (_, rhs))   => Subtract(acc, rhs)
      }
    }

  /** Parse a dice notation string into a DiceExpr */
  def parse(input: String): Either[P.Error, DiceExpr] =
    (spaces.with1 *> diceExpr <* spaces).parseAll(input)
