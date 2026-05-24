package net.andimiller.mcp.apps

import io.circe.parser
import io.circe.syntax.*
import munit.FunSuite

class ResourceUiMetadataSuite extends FunSuite:

  test("empty encodes to {}") {
    assertEquals(ResourceUiMetadata().asJson.deepDropNullValues.noSpaces, "{}")
  }

  test("permissions use the hyphenated spec strings") {
    val ui = ResourceUiMetadata(permissions = Some(List(AppPermission.Camera, AppPermission.ClipboardWrite)))
    assertEquals(ui.asJson.deepDropNullValues.noSpaces, """{"permissions":["camera","clipboard-write"]}""")
  }

  test("csp + prefersBorder round-trip") {
    val ui = ResourceUiMetadata(
      csp = Some(CspPolicy(connect = Some(List("self", "https://api.example.com")))),
      prefersBorder = Some(true)
    )
    val json = ui.asJson.deepDropNullValues
    assertEquals(
      json.noSpaces,
      """{"csp":{"connect":["self","https://api.example.com"]},"prefersBorder":true}"""
    )
    assertEquals(parser.decode[ResourceUiMetadata](json.noSpaces), Right(ui))
  }
