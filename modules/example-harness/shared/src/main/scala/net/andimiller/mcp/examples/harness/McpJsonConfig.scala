package net.andimiller.mcp.examples.harness

import io.circe.Decoder
import io.circe.HCursor

/** Claude-style `.mcp.json`:
  * {{{
  * { "mcpServers": {
  *     "name": { "command": "...", "args": [...], "env": { ... } },        // stdio
  *     "name": { "type": "http", "url": "...", "headers": { ... } }        // streamable-http
  * }}
  * }}}
  *
  * Discriminator: `command` -> Stdio; otherwise (or `type: "http"`) -> Http.
  */
enum McpServerSpec:

  case Stdio(command: String, args: List[String], env: Map[String, String])

  case Http(url: String, headers: Map[String, String])

object McpServerSpec:

  given Decoder[McpServerSpec] = (c: HCursor) =>
    val typeField  = c.get[Option[String]]("type").toOption.flatten
    val hasCommand = c.downField("command").succeeded
    if hasCommand && !typeField.contains("http") then
      for
        cmd  <- c.get[String]("command")
        args <- c.getOrElse[List[String]]("args")(Nil)
        env  <- c.getOrElse[Map[String, String]]("env")(Map.empty)
      yield Stdio(cmd, args, env)
    else
      for
        url     <- c.get[String]("url")
        headers <- c.getOrElse[Map[String, String]]("headers")(Map.empty)
      yield Http(url, headers)

final case class McpJsonConfig(mcpServers: Map[String, McpServerSpec]) derives Decoder
