package net.andimiller.mcp.examples.harness

import cats.effect.kernel.Async
import cats.effect.std.Console
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*

/** Handles `sampling/createMessage` — the server asks our client (which has an LLM attached) to run a chat completion.
  * We forward to [[OpenAiClient.chat]] and shape the response back into the MCP wire format. Tool support and
  * image/audio modalities are intentionally omitted in this basic harness; any non-text content arrives as
  * `[unsupported content type]` in the prompt.
  */
object SamplingHandler:

  // ── Wire types (defined locally — core doesn't ship sampling shapes) ──────

  final private case class TextContent(`type`: String, text: String) derives Encoder.AsObject, Decoder

  final private case class SamplingMessage(role: String, content: TextContent) derives Encoder.AsObject, Decoder

  final private case class CreateRequest(
      messages: List[SamplingMessage],
      systemPrompt: Option[String] = None,
      maxTokens: Option[Int] = None,
      temperature: Option[Double] = None,
      stopSequences: Option[List[String]] = None,
      includeContext: Option[String] = None
  ) derives Decoder

  final private case class CreateResponse(
      role: String,
      content: TextContent,
      model: String,
      stopReason: Option[String] = None
  ) derives Encoder.AsObject

  // ── Handler entry point ───────────────────────────────────────────────────

  def handle[F[_]: Async: Console](
      llm: OpenAiClient[F],
      modelLabel: String
  )(id: RequestId, params: Option[Json]): F[Either[JsonRpcError, Json]] =
    val parsed = params
      .toRight(JsonRpcError.invalidParams("missing params"))
      .flatMap(p =>
        p.as[CreateRequest].leftMap(e => JsonRpcError.invalidParams(s"sampling/createMessage: ${e.getMessage}"))
      )

    parsed match
      case Left(err)  => Async[F].pure(Left(err))
      case Right(req) =>
        val sys     = req.systemPrompt.toList.map(OpenAiTypes.ChatMessage.system)
        val msgs    = req.messages.map(m => OpenAiTypes.ChatMessage(role = m.role, content = Some(m.content.text)))
        val preview =
          msgs.lastOption.flatMap(_.content).map(s => s.take(80) + (if s.length > 80 then "…" else "")).getOrElse("")
        val info = s"sampling: server requested completion of ${msgs.size} message(s) — \"$preview\""

        Console[F].println(Theme.info(info)) *>
          llm.chat(sys ++ msgs, Nil).attempt.map {
            case Left(err)   => Left(JsonRpcError.internalError(s"LLM call failed: ${err.getMessage}"))
            case Right(chat) =>
              chat.choices.headOption.flatMap(_.message.content) match
                case None       => Left(JsonRpcError.internalError("LLM returned no text content"))
                case Some(text) =>
                  val resp = CreateResponse(
                    role = "assistant",
                    content = TextContent("text", text),
                    model = modelLabel,
                    stopReason = chat.choices.headOption.flatMap(_.finish_reason)
                  )
                  Right(resp.asJson.deepDropNullValues)
          }
