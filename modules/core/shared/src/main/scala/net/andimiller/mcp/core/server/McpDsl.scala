package net.andimiller.mcp.core.server

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.protocol.*

abstract class McpDsl extends McpDslCompat

trait McpDslCompat:

  def tool: ToolBuilder.Empty[IO] = Tool.builder[IO]

  def resource: McpResource.type = McpResource

  def prompt: Prompt.type = Prompt