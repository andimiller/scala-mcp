package net.andimiller.mcp.core.protocol

import io.circe.{Encoder, Decoder, Json, JsonObject}
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.schema.JsonSchema

/** Tool definition in MCP protocol */
case class ToolDefinition(
  name: String,
  description: String,
  inputSchema: Json,
  outputSchema: Option[Json] = None
) derives Encoder.AsObject, Decoder

/** Request to call a tool */
case class ToolCall(
  name: String,
  arguments: Json
) derives Encoder.AsObject, Decoder

/**
 * Result of a tool execution, parameterised on the success-value type so the builder DSL
 * can auto-derive the output schema.
 *
 * Branches:
 *   - `Success(value)` — structured success; encoded as both human-readable text (the JSON
 *     stringified) and `structuredContent` (the JSON itself).
 *   - `Text(text)` — free-form text response.
 *   - `Error(message)` — failure with a human-readable message; sets `isError = true`.
 *   - `Raw(content, structuredContent, isError)` — escape hatch for advanced cases (custom
 *     content lists, structured payloads that aren't object-shaped, errors with structured
 *     payloads). Used by openapi-proxy whose success type is arbitrary `Json`.
 */
enum ToolResult[+R]:
  case Success(value: R)                       extends ToolResult[R]
  case Text(text: String)                      extends ToolResult[Nothing]
  case Error(message: String)                  extends ToolResult[Nothing]
  case Raw(
    content: List[Content],
    structuredContent: Option[Json] = None,
    isError: Boolean = false
  )                                            extends ToolResult[Nothing]

object ToolResult:

  /** Convert to the wire-level `CallToolResponse`. */
  def toWire[R](r: ToolResult[R])(using enc: Encoder.AsObject[R]): CallToolResponse = r match
    case Success(v) =>
      val j = enc.encodeObject(v).asJson
      CallToolResponse(List(Content.Text(j.noSpaces)), Some(j), isError = false)
    case Text(t)    => CallToolResponse(List(Content.Text(t)), None, isError = false)
    case Error(m)   => CallToolResponse(List(Content.Text(m)), None, isError = true)
    case Raw(c, s, e) => CallToolResponse(c, s, e)

  /** A total `Encoder.AsObject[Nothing]`. The body is unreachable since `Nothing` has no
   *  inhabitants; this lets `toWire` and the builders accept handlers whose `R` infers as
   *  `Nothing` (i.e. handlers that never return a structured `Success`). */
  given Encoder.AsObject[Nothing] with
    def encodeObject(a: Nothing): JsonObject = a

/**
 * Resolves the output-schema question for a given `R`:
 *   - For ordinary `R` with a `JsonSchema[R]` instance, produces `Some(schema-as-json)`.
 *   - For `R = Nothing` (no structured success), produces `None`.
 *
 * Used by the tool builders so a single `.runResult[R]` API works for both schema-bearing
 * and schema-less handlers.
 */
trait OutputSchema[R]:
  def asJson: Option[Json]

object OutputSchema:
  /** Lower-priority instance: derive from `JsonSchema[R]`. */
  given derived[R](using js: JsonSchema[R]): OutputSchema[R] with
    def asJson: Option[Json] = Some(JsonSchema.toJson[R])

  /** Higher-priority instance for the bottom type — pinned in the companion object so it's
   *  preferred when `R = Nothing`. */
  given OutputSchema[Nothing] with
    def asJson: Option[Json] = None

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
  structuredContent: Option[Json] = None,
  isError: Boolean = false
) derives Encoder.AsObject, Decoder
