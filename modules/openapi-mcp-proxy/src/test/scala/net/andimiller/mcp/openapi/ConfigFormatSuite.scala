package net.andimiller.mcp.openapi

import io.circe.Json
import munit.FunSuite

class ConfigFormatSuite extends FunSuite:

  test("serversKey: claude/claude-desktop/cursor use mcpServers; opencode uses mcp") {
    assertEquals(ConfigFormat.Claude.serversKey, "mcpServers")
    assertEquals(ConfigFormat.ClaudeDesktop.serversKey, "mcpServers")
    assertEquals(ConfigFormat.Cursor.serversKey, "mcpServers")
    assertEquals(ConfigFormat.OpenCode.serversKey, "mcp")
  }

  test("emptyFile: claude shapes have an empty mcpServers; opencode adds $schema") {
    assertEquals(ConfigFormat.Claude.emptyFile, Json.obj("mcpServers" -> Json.obj()))
    assertEquals(ConfigFormat.Cursor.emptyFile, Json.obj("mcpServers" -> Json.obj()))
    val openCode = ConfigFormat.OpenCode.emptyFile
    assertEquals(openCode.hcursor.get[String]("$schema").toOption, Some("https://opencode.ai/config.json"))
    assertEquals(openCode.hcursor.downField("mcp").as[Map[String, Json]].toOption, Some(Map.empty))
  }

  test("mkEntry / parseArgs round-trip for Claude format") {
    val entry = ConfigFormat.Claude.mkEntry("https://api.example.com/spec.json", List("listUsers", "getUserById"))
    assertEquals(entry.hcursor.get[String]("command").toOption, Some("openapi-mcp-proxy"))
    val args = ConfigFormat.Claude.parseArgs(entry)
    assertEquals(args, Some(List("proxy", "https://api.example.com/spec.json", "listUsers", "getUserById")))
  }

  test("mkEntry / parseArgs round-trip for OpenCode format (uses command array)") {
    val entry = ConfigFormat.OpenCode.mkEntry("./spec.yaml", List("op"))
    assertEquals(entry.hcursor.get[String]("type").toOption, Some("local"))
    assertEquals(
      entry.hcursor.get[List[String]]("command").toOption,
      Some(List("openapi-mcp-proxy", "proxy", "./spec.yaml", "op"))
    )
    val args = ConfigFormat.OpenCode.parseArgs(entry)
    assertEquals(args, Some(List("openapi-mcp-proxy", "proxy", "./spec.yaml", "op")))
  }

  test("validate: ClaudeDesktop is gated by macOS detection (other formats always pass)") {
    assertEquals(ConfigFormat.Claude.validate, Right(()))
    assertEquals(ConfigFormat.Cursor.validate, Right(()))
    assertEquals(ConfigFormat.OpenCode.validate, Right(()))
    val isMac = System.getProperty("os.name", "").toLowerCase.contains("mac")
    if isMac then assertEquals(ConfigFormat.ClaudeDesktop.validate, Right(()))
    else assert(ConfigFormat.ClaudeDesktop.validate.isLeft)
  }
