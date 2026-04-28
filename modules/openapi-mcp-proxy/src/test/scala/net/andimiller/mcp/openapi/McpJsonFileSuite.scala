package net.andimiller.mcp.openapi

import munit.FunSuite

class McpJsonFileSuite extends FunSuite:

  test("deriveServerName uses the spec title when supplied") {
    val name = McpJsonFile.deriveServerName("https://api.example.com/openapi.json", Some("EVE Swagger Interface"))
    assertEquals(name, "openapi-eve-swagger-interface")
  }

  test("deriveServerName normalises punctuation and collapses dashes") {
    assertEquals(
      McpJsonFile.deriveServerName("x", Some("My Cool API: v2!!")),
      "openapi-my-cool-api-v2"
    )
  }

  test("deriveServerName falls back to URL host when no title") {
    val name = McpJsonFile.deriveServerName("https://api.example.com/v1/spec.json", None)
    assertEquals(name, "openapi-api.example.com")
  }

  test("deriveServerName falls back to file basename (no extension) for local paths") {
    val name = McpJsonFile.deriveServerName("./specs/petstore.yaml", None)
    assertEquals(name, "openapi-petstore")
  }

  test("deriveServerName ignores empty/whitespace titles and falls back") {
    val name = McpJsonFile.deriveServerName("./spec.json", Some("   "))
    assertEquals(name, "openapi-spec")
  }
