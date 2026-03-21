package mcp.core.protocol

import io.circe.{Encoder, Decoder, Json}
import mcp.core.protocol.content.Content
import mcp.core.codecs.CirceCodecs.given

/** Tool definition in MCP protocol */
case class ToolDefinition(
  name: String,
  description: String,
  inputSchema: Json,
  outputSchema: Json
) derives Encoder.AsObject, Decoder

/** Request to call a tool */
case class ToolCall(
  name: String,
  arguments: Json
) derives Encoder.AsObject, Decoder

/** Result of a tool execution */
case class ToolResult(
  content: List[Content],
  isError: Boolean = false
) derives Encoder.AsObject, Decoder

object ToolResult:
  def success(content: Content*): ToolResult =
    ToolResult(content.toList, isError = false)

  def error(message: String): ToolResult =
    ToolResult(List(Content.Text(message)), isError = true)

  def text(text: String): ToolResult =
    ToolResult(List(Content.Text(text)), isError = false)

/** Request to list available tools */
case class ListToolsRequest(
  cursor: Option[String] = None
) derives Encoder.AsObject, Decoder

/** Response listing available tools */
case class ListToolsResponse(
  tools: List[ToolDefinition],
  nextCursor: Option[String] = None
) derives Encoder.AsObject, Decoder

/** Request to call a tool */
case class CallToolRequest(
  name: String,
  arguments: Json
) derives Encoder.AsObject, Decoder

/** Response from calling a tool */
case class CallToolResponse(
  content: List[Content],
  isError: Boolean = false
) derives Encoder.AsObject, Decoder
