package net.andimiller.mcp.core.protocol

import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.schema.JsonSchema

class ToolResultSuite extends FunSuite:

  case class Demo(name: String, count: Int) derives Encoder.AsObject, JsonSchema

  test("Success.toWire emits text content + structuredContent + isError=false") {
    val r    = ToolResult.Success(Demo("foo", 3))
    val wire = ToolResult.toWire(r)
    assertEquals(wire.isError, false)
    assertEquals(wire.structuredContent, Some(Demo("foo", 3).asJson))
    assertEquals(wire.content, List(Content.Text(Demo("foo", 3).asJson.noSpaces)))
  }

  test("Text.toWire emits text content, no structuredContent, isError=false") {
    val wire = ToolResult.toWire[Nothing](ToolResult.Text("hello"))
    assertEquals(wire.isError, false)
    assertEquals(wire.structuredContent, None)
    assertEquals(wire.content, List(Content.Text("hello")))
  }

  test("Error.toWire emits text content, no structuredContent, isError=true") {
    val wire = ToolResult.toWire[Nothing](ToolResult.Error("boom"))
    assertEquals(wire.isError, true)
    assertEquals(wire.structuredContent, None)
    assertEquals(wire.content, List(Content.Text("boom")))
  }

  test("Raw.toWire passes its fields through verbatim") {
    val payload             = Json.obj("k" -> "v".asJson)
    val rawContent          = List[Content](Content.Text("body"), Content.Image("imgdata", "image/png"))
    val raw: ToolResult.Raw = ToolResult.Raw(
      content = rawContent,
      structuredContent = Some(payload),
      isError = false
    )
    val wire = ToolResult.toWire[Nothing](raw)
    assertEquals(wire.content, rawContent)
    assertEquals(wire.structuredContent, Some(payload))
    assertEquals(wire.isError, false)
  }

  test("OutputSchema[Demo] derives a schema") {
    val s = summon[OutputSchema[Demo]].asJson
    assert(s.isDefined, "OutputSchema[Demo] should produce a schema")
    assertEquals(s.flatMap(_.hcursor.downField("type").as[String].toOption), Some("object"))
  }

  test("OutputSchema[Nothing] produces no schema") {
    assertEquals(summon[OutputSchema[Nothing]].asJson, None)
  }
