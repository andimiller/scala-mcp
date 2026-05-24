package net.andimiller.mcp.apps

import io.circe.parser
import io.circe.syntax.*
import munit.FunSuite

class ToolUiMetadataSuite extends FunSuite:

  test("encodes resourceUri alone (visibility omitted)") {
    val ui   = ToolUiMetadata("ui://clock")
    val json = ui.asJson.deepDropNullValues
    assertEquals(json.noSpaces, """{"resourceUri":"ui://clock"}""")
  }

  test("encodes both visibility values in the correct strings") {
    val ui   = ToolUiMetadata("ui://x", Some(List(ToolVisibility.Model, ToolVisibility.App)))
    val json = ui.asJson.deepDropNullValues
    assertEquals(json.noSpaces, """{"resourceUri":"ui://x","visibility":["model","app"]}""")
  }

  test("decodes the spec's sample shape") {
    val raw = """{"resourceUri":"ui://clock","visibility":["app"]}"""
    val ui  = parser.decode[ToolUiMetadata](raw)
    assertEquals(ui, Right(ToolUiMetadata("ui://clock", Some(List(ToolVisibility.App)))))
  }

  test("unknown visibility string fails to decode with a useful message") {
    val raw = """{"resourceUri":"ui://x","visibility":["nope"]}"""
    val ui  = parser.decode[ToolUiMetadata](raw)
    assert(ui.isLeft)
    assert(ui.swap.toOption.exists(_.getMessage.contains("nope")))
  }
