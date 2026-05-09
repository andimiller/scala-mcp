package net.andimiller.mcp.examples.harness

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json

/** OpenAI Chat Completions API — minimal shapes the harness needs. Send-side payloads should be stripped of nulls
  * (`.deepDropNullValues`) since some compatible servers reject explicit nulls.
  */
object OpenAiTypes:

  /** OpenAI requires `function.arguments` to be a JSON-encoded string, not a JSON value. */
  final case class FunctionCall(name: String, arguments: String) derives Encoder.AsObject, Decoder

  final case class ToolCall(
      id: String,
      `type`: String = "function",
      function: FunctionCall
  ) derives Encoder.AsObject,
        Decoder

  final case class ChatMessage(
      role: String,
      content: Option[String] = None,
      tool_calls: Option[List[ToolCall]] = None,
      tool_call_id: Option[String] = None,
      name: Option[String] = None,
      // Some OpenAI-compatible providers expose chain-of-thought from reasoning models here:
      //   - DeepSeek / GLM / Qwen via their own gateways: `reasoning_content`
      //   - OpenRouter: `reasoning`
      // Per provider convention these should NOT be echoed back in subsequent requests; the Repl
      // strips them from the message before appending to history.
      reasoning_content: Option[String] = None,
      reasoning: Option[String] = None
  ) derives Encoder.AsObject,
        Decoder

  object ChatMessage:

    def system(content: String): ChatMessage = ChatMessage("system", content = Some(content))

    def user(content: String): ChatMessage = ChatMessage("user", content = Some(content))

    def assistantText(content: String): ChatMessage = ChatMessage("assistant", content = Some(content))

    def assistantTools(calls: List[ToolCall]): ChatMessage =
      ChatMessage("assistant", content = None, tool_calls = Some(calls))

    def tool(callId: String, content: String): ChatMessage =
      ChatMessage("tool", content = Some(content), tool_call_id = Some(callId))

  final case class FunctionDef(
      name: String,
      description: String,
      parameters: Json
  ) derives Encoder.AsObject,
        Decoder

  final case class ToolDef(
      `type`: String = "function",
      function: FunctionDef
  ) derives Encoder.AsObject,
        Decoder

  final case class ChatRequest(
      model: String,
      messages: List[ChatMessage],
      tools: Option[List[ToolDef]] = None,
      tool_choice: Option[String] = None,
      stream: Option[Boolean] = None
  ) derives Encoder.AsObject,
        Decoder

  final case class Choice(
      index: Int,
      message: ChatMessage,
      finish_reason: Option[String] = None
  ) derives Encoder.AsObject,
        Decoder

  final case class ChatResponse(
      id: Option[String] = None,
      choices: List[Choice]
  ) derives Encoder.AsObject,
        Decoder

  // ── Streaming (SSE) chunks ───────────────────────────────────────
  // Tool calls arrive piecewise: the `id` and `function.name` typically come in the first chunk
  // mentioning a given `index`, then `function.arguments` streams in fragments. Aggregation is
  // keyed by `index`, with arguments concatenated.

  final case class FunctionCallDelta(
      name: Option[String] = None,
      arguments: Option[String] = None
  ) derives Decoder

  final case class ToolCallDelta(
      index: Int,
      id: Option[String] = None,
      `type`: Option[String] = None,
      function: Option[FunctionCallDelta] = None
  ) derives Decoder

  final case class ChatDelta(
      role: Option[String] = None,
      content: Option[String] = None,
      reasoning_content: Option[String] = None,
      reasoning: Option[String] = None,
      tool_calls: Option[List[ToolCallDelta]] = None
  ) derives Decoder

  final case class StreamChoice(
      index: Int,
      delta: ChatDelta,
      finish_reason: Option[String] = None
  ) derives Decoder

  final case class StreamChunk(choices: List[StreamChoice]) derives Decoder

  /** Events emitted by `OpenAiClient.chatStream`. The Repl prints `ContentToken` / `ReasoningToken` live as they arrive
    * and reads the assembled `ChatMessage` from `Final`, which is emitted exactly once after the upstream SSE stream
    * ends.
    */
  enum StreamEvent:

    case ContentToken(text: String)

    case ReasoningToken(text: String)

    case Final(message: ChatMessage)
