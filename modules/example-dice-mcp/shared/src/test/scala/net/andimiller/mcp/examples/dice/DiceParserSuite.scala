package net.andimiller.mcp.examples.dice

import net.andimiller.mcp.examples.dice.DiceParser.*

class DiceParserSuite extends munit.FunSuite:

  test("parse simple dice roll") {
    val result = DiceParser.parse("1d6")
    assertEquals(result, Right(Roll(DiceRoll(1, 6))))
  }

  test("parse multi-dice roll") {
    val result = DiceParser.parse("3d8")
    assertEquals(result, Right(Roll(DiceRoll(3, 8))))
  }

  test("parse dice roll with addition") {
    val result = DiceParser.parse("2d20 + 5")
    assertEquals(result, Right(Add(Roll(DiceRoll(2, 20)), Constant(5))))
  }

  test("parse dice roll with subtraction") {
    val result = DiceParser.parse("3d4 - 2")
    assertEquals(result, Right(Subtract(Roll(DiceRoll(3, 4)), Constant(2))))
  }

  test("parse with no spaces around operator") {
    val result = DiceParser.parse("1d6+3")
    assertEquals(result, Right(Add(Roll(DiceRoll(1, 6)), Constant(3))))
  }

  test("parse two dice rolls added together") {
    val result = DiceParser.parse("2d6 + 1d2")
    assertEquals(result, Right(Add(Roll(DiceRoll(2, 6)), Roll(DiceRoll(1, 2)))))
  }

  test("parse chain of three dice rolls (left-associative)") {
    val result = DiceParser.parse("1d4 + 2d6 + 3d8")
    assertEquals(
      result,
      Right(Add(Add(Roll(DiceRoll(1, 4)), Roll(DiceRoll(2, 6))), Roll(DiceRoll(3, 8))))
    )
  }

  test("parse mixed dice and constants") {
    val result = DiceParser.parse("1d20 + 5 - 1d4")
    assertEquals(
      result,
      Right(Subtract(Add(Roll(DiceRoll(1, 20)), Constant(5)), Roll(DiceRoll(1, 4))))
    )
  }

  test("parse with leading/trailing whitespace") {
    val result = DiceParser.parse("  2d10  ")
    assertEquals(result, Right(Roll(DiceRoll(2, 10))))
  }

  test("reject empty string") {
    assert(DiceParser.parse("").isLeft)
  }

  test("reject invalid input") {
    assert(DiceParser.parse("hello").isLeft)
  }

  test("reject missing sides") {
    assert(DiceParser.parse("2d").isLeft)
  }
