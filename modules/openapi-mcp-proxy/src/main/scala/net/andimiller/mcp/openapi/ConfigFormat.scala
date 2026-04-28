package net.andimiller.mcp.openapi

import io.circe.Json
import java.nio.file.Path
import java.nio.file.Paths

enum ConfigFormat:

  case Claude, ClaudeDesktop, Cursor, OpenCode

  def filePath: Path = this match
    case Claude        => Paths.get(".mcp.json")
    case ClaudeDesktop =>
      Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Claude",
        "claude_desktop_config.json")
    case Cursor   => Paths.get(".cursor", "mcp.json")
    case OpenCode => Paths.get("opencode.json")

  def serversKey: String = this match
    case Claude | ClaudeDesktop => "mcpServers"
    case Cursor                 => "mcpServers"
    case OpenCode               => "mcp"

  def emptyFile: Json = this match
    case Claude | ClaudeDesktop => Json.obj("mcpServers" -> Json.obj())
    case Cursor                 => Json.obj("mcpServers" -> Json.obj())
    case OpenCode               =>
      Json.obj(
        "$schema" -> Json.fromString("https://opencode.ai/config.json"),
        "mcp"     -> Json.obj()
      )

  def mkEntry(specSource: String, operationIds: List[String]): Json = this match
    case Claude | ClaudeDesktop | Cursor =>
      Json.obj(
        "command" -> Json.fromString("openapi-mcp-proxy"),
        "args"    -> Json.arr((List("proxy", specSource) ++ operationIds).map(Json.fromString)*)
      )
    case OpenCode =>
      Json.obj(
        "type"    -> Json.fromString("local"),
        "command" -> Json.arr((List("openapi-mcp-proxy", "proxy", specSource) ++ operationIds).map(Json.fromString)*)
      )

  def parseArgs(json: Json): Option[List[String]] = this match
    case Claude | ClaudeDesktop | Cursor =>
      json.hcursor.downField("args").as[List[String]].toOption
    case OpenCode =>
      json.hcursor.downField("command").as[List[String]].toOption

  def validate: Either[String, Unit] = this match
    case ClaudeDesktop if !System.getProperty("os.name", "").toLowerCase.contains("mac") =>
      Left("claude-desktop is currently only supported on macOS")
    case _ => Right(())
