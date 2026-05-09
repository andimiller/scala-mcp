package net.andimiller.mcp.examples.harness

import scala.collection.immutable.SortedMap

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*

import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.Authorization

/** Tiny OpenAI-compatible Chat Completions client.
  *
  * Two flavours:
  *   - [[chat]] — single POST, full body returned. Useful for tests/debug.
  *   - [[chatStream]] — Server-Sent Events streaming. Emits a `Stream[F, StreamEvent]` so the caller can print tokens
  *     live and recover the assembled assistant `ChatMessage` from the final `Final` event.
  */
final class OpenAiClient[F[_]: Async](
    httpClient: Client[F],
    baseUri: Uri,
    apiKey: String,
    model: String
):

  import OpenAiTypes.*

  private val auth = Authorization(Credentials.Token(AuthScheme.Bearer, apiKey))

  private def buildRequest(messages: List[ChatMessage], tools: List[ToolDef], streaming: Boolean): Request[F] =
    val body = ChatRequest(
      model = model,
      messages = messages,
      tools = Option.when(tools.nonEmpty)(tools),
      tool_choice = Option.when(tools.nonEmpty)("auto"),
      stream = Option.when(streaming)(true)
    ).asJson.deepDropNullValues
    Request[F](Method.POST, baseUri / "chat" / "completions")
      .withHeaders(Headers(auth))
      .withEntity(body)

  def chat(messages: List[ChatMessage], tools: List[ToolDef]): F[ChatResponse] =
    httpClient.expect[ChatResponse](buildRequest(messages, tools, streaming = false))(jsonOf[F, ChatResponse])

  // ── Streaming ─────────────────────────────────────────────────────

  /** Per-tool-call delta accumulator. `id` and `name` typically arrive once on first reference; `args` is appended as
    * the JSON-encoded arguments string streams in fragments.
    */
  final private case class ToolCallAcc(id: Option[String] = None, name: Option[String] = None, args: String = "")

  final private case class StreamState(
      content: String = "",
      reasoning: String = "",
      // Whichever reasoning field the provider used — preserved when reconstructing the message
      // so the final ChatMessage matches what the server emitted (DeepSeek/GLM use
      // `reasoning_content`, OpenRouter uses `reasoning`).
      reasoningKind: Option[String] = None,
      tools: SortedMap[Int, ToolCallAcc] = SortedMap.empty[Int, ToolCallAcc]
  )

  def chatStream(messages: List[ChatMessage], tools: List[ToolDef]): Stream[F, StreamEvent] =
    val req = buildRequest(messages, tools, streaming = true)
    Stream.eval(Ref[F].of(StreamState())).flatMap { state =>
      val sse: Stream[F, StreamEvent] =
        Stream
          .resource(httpClient.run(req))
          .flatMap(_.body.through(ServerSentEvent.decoder[F]))
          .evalMap(handleEvent(state))
          .flatMap(Stream.emits)
      val finalEvt: Stream[F, StreamEvent] =
        Stream.eval(state.get.map(s => StreamEvent.Final(buildMessage(s))))
      sse ++ finalEvt
    }

  /** Decode one SSE event and fold any deltas into `state`, returning the live tokens to emit. */
  private def handleEvent(state: Ref[F, StreamState])(event: ServerSentEvent): F[List[StreamEvent]] =
    event.data match
      case None      => Async[F].pure(Nil)
      case Some(raw) =>
        val data = raw.trim
        // Some providers send the OpenAI-style `[DONE]` sentinel; others just close the body.
        // Either way the upstream stream ends, and the Final event is appended after.
        if data.isEmpty || data === "[DONE]" then Async[F].pure(Nil)
        else
          decode[OpenAiTypes.StreamChunk](data) match
            case Left(err) =>
              Async[F].raiseError(new RuntimeException(s"bad SSE chunk: ${err.getMessage}\n  data: $data"))
            case Right(chunk) =>
              chunk.choices.headOption.fold(Async[F].pure(List.empty[StreamEvent]))(c => applyDelta(state, c.delta))

  private def applyDelta(state: Ref[F, StreamState], d: OpenAiTypes.ChatDelta): F[List[StreamEvent]] =
    state.modify { s =>
      val (s1, contentEvts) = d.content match
        case Some(c) if c.nonEmpty => (s.copy(content = s.content + c), List(StreamEvent.ContentToken(c)))
        case _                     => (s, Nil)
      val (s2, reasoningEvts) = d.reasoning_content match
        case Some(c) if c.nonEmpty =>
          (
            s1.copy(reasoning = s1.reasoning + c, reasoningKind = Some("reasoning_content")),
            List(StreamEvent.ReasoningToken(c))
          )
        case _ =>
          d.reasoning match
            case Some(c) if c.nonEmpty =>
              (
                s1.copy(reasoning = s1.reasoning + c, reasoningKind = Some("reasoning")),
                List(StreamEvent.ReasoningToken(c))
              )
            case _ => (s1, Nil)
      val s3 = d.tool_calls.fold(s2) { calls =>
        calls.foldLeft(s2) { (st, tcd) =>
          val acc    = st.tools.getOrElse(tcd.index, ToolCallAcc())
          val newAcc = acc.copy(
            id = tcd.id.orElse(acc.id),
            name = tcd.function.flatMap(_.name).orElse(acc.name),
            args = tcd.function.flatMap(_.arguments).fold(acc.args)(frag => acc.args + frag)
          )
          st.copy(tools = st.tools.updated(tcd.index, newAcc))
        }
      }
      (s3, contentEvts ++ reasoningEvts)
    }

  private def buildMessage(s: StreamState): ChatMessage =
    val toolCalls =
      if s.tools.isEmpty then None
      else
        Some(s.tools.toList.map { case (_, acc) =>
          ToolCall(
            id = acc.id.getOrElse(""),
            function = FunctionCall(name = acc.name.getOrElse(""), arguments = acc.args)
          )
        })
    ChatMessage(
      role = "assistant",
      content = Option.when(s.content.nonEmpty)(s.content),
      tool_calls = toolCalls,
      reasoning_content =
        Option.when(s.reasoningKind.contains("reasoning_content") && s.reasoning.nonEmpty)(s.reasoning),
      reasoning = Option.when(s.reasoningKind.contains("reasoning") && s.reasoning.nonEmpty)(s.reasoning)
    )
