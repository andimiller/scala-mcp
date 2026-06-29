package net.andimiller.mcp.http4s

import scala.concurrent.duration.*

import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*

import net.andimiller.mcp.core.client.ClientHandler
import net.andimiller.mcp.core.client.ClientSession
import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.client.McpProtocol
import net.andimiller.mcp.core.client.UninitializedMcpClient
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.ClientCapabilities
import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.transport.MessageChannel

import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*
import org.typelevel.log4cats.LoggerFactory

/** MCP client over the streamable HTTP transport (single endpoint, POST + SSE GET + DELETE). Counterpart to
  * [[StreamableHttpTransport]] on the server side.
  *
  * Two layers:
  *   - low-level [[fromHttpClient]] returns an [[UninitializedMcpClient]]
  *   - high-level [[builder]] wraps fromHttpClient + initialize and yields a ready [[McpClient]]
  *
  * Wire shape (matches `StreamableHttpTransport`):
  *   - `POST {endpoint}` with JSON body — the `Mcp-Session-Id` header is captured from the `initialize` response and
  *     attached to all subsequent requests.
  *   - `GET {endpoint}` with `Accept: text/event-stream` — long-poll SSE for server-initiated traffic. Opened lazily
  *     once the session id is known.
  *   - `DELETE {endpoint}` — sent on resource release to terminate the session.
  */
object StreamableHttpMcpClient:

  private val mcpSessionId = ci"Mcp-Session-Id"

  // ── Low-level API ─────────────────────────────────────────────────

  def fromHttpClient[F[_]: Async: LoggerFactory](
      httpClient: Client[F],
      endpoint: Uri
  ): Resource[F, UninitializedMcpClient[F]] =
    fromHttpClient(httpClient, endpoint, Headers.empty, openSseStream = true, ClientHandler.noop[F])

  def fromHttpClient[F[_]: Async: LoggerFactory](
      httpClient: Client[F],
      endpoint: Uri,
      extraHeaders: Headers,
      openSseStream: Boolean,
      handler: ClientHandler[F]
  ): Resource[F, UninitializedMcpClient[F]] =
    val logger = LoggerFactory[F].getLoggerFromName("net.andimiller.mcp.http4s.StreamableHttpMcpClient")
    for
      sessionId    <- Resource.eval(Ref[F].of(Option.empty[String]))
      sessionReady <- Resource.eval(Deferred[F, String])
      // `subscribed` flips once the SSE GET stream has been established AND the server has had
      // a chance to pull from its outbound `fs2.concurrent.Topic`. Without this, any
      // server-initiated message (elicitation/create, sampling/createMessage, notifications) sent
      // *before* our subscriber attaches is silently dropped by the topic — the harness's first
      // tool call could trigger an elicitation request that vanishes into thin air.
      subscribed <- Resource.eval(Deferred[F, Unit])
      shutdown   <- Resource.eval(Deferred[F, Unit])
      inbound    <- Resource.eval(Queue.unbounded[F, Message])
      // Use start + bracket-style cleanup rather than `.background` so we control the shutdown
      // sequence: signal `shutdown` (the SSE stream's interruptWhen sees it and exits), then
      // race the fiber's join against a short timeout, finally cancel if it hasn't exited.
      // Necessary because some HTTP clients don't unblock streaming reads on plain cancel.
      _ <-
        if openSseStream then
          // Spawn the SSE consumer; on cleanup, cancel it but don't block forever waiting for
          // streaming HTTP reads that may not honor cancellation cleanly. A short timeout keeps
          // the resource cleanup well-behaved even when the underlying connection is misbehaving.
          Resource
            .make(
              sseConsumer(httpClient, endpoint, sessionReady, subscribed, extraHeaders, inbound)
                .interruptWhen(shutdown.get.attempt)
                .compile
                .drain
                .start
            )(fiber =>
              shutdown.complete(()).attempt.void *>
                fiber.cancel.timeoutTo(2.seconds, Async[F].unit).attempt.flatMap {
                  case Left(t) =>
                    logger.warn(Map("task" -> "sseFiberCancel"), t)("background task failure ignored")
                  case Right(_) => Async[F].unit
                }
            )
            .void
        else Resource.eval(subscribed.complete(()).attempt.void)
      channel = httpChannel(httpClient, endpoint, sessionId, sessionReady, inbound, extraHeaders)
      inner  <- ClientSession.resource[F](channel, handler)
      _      <- Resource.onFinalize(
             deleteSession(httpClient, endpoint, sessionId, extraHeaders).attempt.flatMap {
               case Left(t)  => logger.warn(Map("task" -> "deleteSession"), t)("background task failure ignored")
               case Right(_) => Async[F].unit
             }
           )
    yield new UninitializedMcpClient[F]:
      def initialize(
          info: Implementation,
          capabilities: ClientCapabilities,
          protocolVersion: String
      ): F[McpClient[F]] =
        inner.initialize(info, capabilities, protocolVersion).flatMap { client =>
          // Block until our SSE GET is subscribed on the server side so any server-initiated
          // request (e.g. elicitation/create) issued during the first tool call is delivered.
          // Capped at 2s so an SSE failure (which sseConsumer raises loudly) doesn't hang
          // initialize indefinitely — the user will then see the underlying error.
          subscribed.get.timeoutTo(2.seconds, Async[F].unit).as(client)
        }

  // ── High-level API: builder ───────────────────────────────────────

  def builder[F[_]: Async: LoggerFactory](
      httpClient: Client[F],
      endpoint: Uri
  ): HttpClientBuilder[F] =
    HttpClientBuilder.empty[F](httpClient, endpoint)

  // ── Internals ─────────────────────────────────────────────────────

  /** Build the [[MessageChannel]] adapter. POSTs each outbound message; for [[Message.Request]] also enqueues the POST
    * response body onto the inbound queue so the client session's read loop correlates it like any other response.
    */
  private def httpChannel[F[_]: Async](
      httpClient: Client[F],
      endpoint: Uri,
      sessionId: Ref[F, Option[String]],
      sessionReady: Deferred[F, String],
      inbound: Queue[F, Message],
      extraHeaders: Headers
  ): MessageChannel[F] = new MessageChannel[F]:

    def incoming: Stream[F, Message] = Stream.fromQueueUnterminated(inbound)

    def send(message: Message): F[Unit] =
      message match
        case _: Message.Request                            => post(message, expectsResponse = true)
        case _: Message.Notification | _: Message.Response => post(message, expectsResponse = false)

    def close: F[Unit] = Async[F].unit

    private def post(message: Message, expectsResponse: Boolean): F[Unit] =
      for
        currentSession <- sessionId.get
        baseHeaders     = Headers(
                        `Content-Type`(MediaType.application.json),
                        Header.Raw(ci"Accept", "application/json, text/event-stream")
                      ) ++ extraHeaders
        headers = currentSession.fold(baseHeaders)(sid => baseHeaders.put(Header.Raw(mcpSessionId, sid)))
        req     = Request[F](Method.POST, endpoint, headers = headers)
                .withEntity(message.asJson.noSpaces)
        _ <- httpClient.run(req).use { resp =>
               // Capture session id (set by server on initialize). First-write-wins:
               // a server that re-emits Mcp-Session-Id on later responses cannot
               // overwrite the deferred — that would race against any in-flight
               // SSE consumer already gated on the original id.
               val captureSession = resp.headers
                 .get(mcpSessionId)
                 .map(_.head.value)
                 .traverse_ { newId =>
                   sessionId.modify {
                     case None    => (Some(newId), true)
                     case Some(s) => (Some(s), false)
                   }.flatMap {
                     case true  => sessionReady.complete(newId).attempt.void
                     case false => Async[F].unit
                   }
                 }

               captureSession *> {
                 if expectsResponse then
                   resp.bodyText.compile.string.flatMap { body =>
                     if body.trim.isEmpty then Async[F].unit
                     else
                       decode[Message](body) match
                         case Right(m)  => inbound.offer(m)
                         case Left(err) =>
                           Async[F].raiseError(
                             new RuntimeException(
                               s"Invalid JSON-RPC response from $endpoint: ${err.getMessage}\n  body: $body"
                             )
                           )
                   }
                 else
                   // Drain & discard the body for notifications / responses (typically 202 Accepted).
                   resp.body.compile.drain
               }
             }
      yield ()

  /** Open the long-poll SSE stream once the session id is known. Each parsed event's `data` field is decoded as a
    * [[Message]] and enqueued onto the inbound queue.
    *
    * Completes the `subscribed` deferred once the GET response is in hand and successful, so `fromHttpClient`'s
    * `initialize` wrapper can gate `connect` on the SSE subscription being live. A non-success response is raised
    * loudly — silent SSE failures were the symptom of a particularly hard-to-debug class of "server-initiated requests
    * vanish" bugs.
    */
  private def sseConsumer[F[_]: Async](
      httpClient: Client[F],
      endpoint: Uri,
      sessionReady: Deferred[F, String],
      subscribed: Deferred[F, Unit],
      extraHeaders: Headers,
      inbound: Queue[F, Message]
  ): Stream[F, Unit] =
    Stream.eval(sessionReady.get).flatMap { sid =>
      val headers = Headers(
        Header.Raw(ci"Accept", "text/event-stream"),
        Header.Raw(mcpSessionId, sid)
      ) ++ extraHeaders
      val request = Request[F](Method.GET, endpoint, headers = headers)
      Stream
        .resource(httpClient.run(request))
        .flatMap { resp =>
          if !resp.status.isSuccess then
            Stream.eval(
              resp.bodyText.compile.string.attempt.flatMap { body =>
                val snippet = body.toOption.getOrElse("(unreadable)").take(200)
                Async[F].raiseError[Unit](
                  new RuntimeException(
                    s"SSE GET $endpoint failed: HTTP ${resp.status.code} body=$snippet"
                  )
                )
              }
            )
          else
            // Signal subscription-readiness slightly after the GET response is acquired. The
            // server's outbound stream is `topic.subscribe` which only registers a subscriber
            // when the first body chunk is pulled; in practice that first pull happens within a
            // few milliseconds of the response being constructed. 50ms is a comfortable upper
            // bound for a local connection and well below any reasonable server-initiated
            // request latency.
            Stream.eval(Async[F].sleep(50.millis) *> subscribed.complete(()).attempt.void) ++
              resp.body
                .through(ServerSentEvent.decoder[F])
                .evalMap { event =>
                  event.data match
                    case Some(data) =>
                      decode[Message](data) match
                        case Right(m)  => inbound.offer(m)
                        case Left(err) =>
                          Async[F].raiseError(
                            new RuntimeException(s"Invalid SSE message: ${err.getMessage}\n  data: $data")
                          )
                    case None => Async[F].unit
                }
        }
    }

  private def deleteSession[F[_]: Async](
      httpClient: Client[F],
      endpoint: Uri,
      sessionId: Ref[F, Option[String]],
      extraHeaders: Headers
  ): F[Unit] =
    sessionId.get.flatMap {
      case None      => Async[F].unit
      case Some(sid) =>
        val headers = Headers(Header.Raw(mcpSessionId, sid)) ++ extraHeaders
        val req     = Request[F](Method.DELETE, endpoint, headers = headers)
        httpClient.run(req).use(_.body.compile.drain).void
    }

/** Fluent builder for [[StreamableHttpMcpClient]] that wraps fromHttpClient + initialize. */
final class HttpClientBuilder[F[_]: Async: LoggerFactory] private (
    private val httpClient: Client[F],
    private val endpoint: Uri,
    private val extraHeaders: Headers,
    private val openSseStream: Boolean,
    private val info: Option[Implementation],
    private val capabilities: ClientCapabilities,
    private val protocolVersion: String,
    private val handler: ClientHandler[F]
):

  private def copy(
      extraHeaders: Headers = extraHeaders,
      openSseStream: Boolean = openSseStream,
      info: Option[Implementation] = info,
      capabilities: ClientCapabilities = capabilities,
      protocolVersion: String = protocolVersion,
      handler: ClientHandler[F] = handler
  ): HttpClientBuilder[F] =
    new HttpClientBuilder[F](
      httpClient, endpoint, extraHeaders, openSseStream, info, capabilities, protocolVersion, handler
    )

  def withHeaders(headers: Headers): HttpClientBuilder[F] = copy(extraHeaders = headers)

  def withSse(enabled: Boolean): HttpClientBuilder[F] = copy(openSseStream = enabled)

  def withInfo(info: Implementation): HttpClientBuilder[F] = copy(info = Some(info))

  def withCapabilities(capabilities: ClientCapabilities): HttpClientBuilder[F] = copy(capabilities = capabilities)

  def withProtocolVersion(version: String): HttpClientBuilder[F] = copy(protocolVersion = version)

  def withHandler(handler: ClientHandler[F]): HttpClientBuilder[F] = copy(handler = handler)

  def connect: Resource[F, McpClient[F]] =
    for
      info0 <- Resource.eval(
                 info.fold[F[Implementation]](
                   Async[F].raiseError(new IllegalStateException("HttpClientBuilder: info is required"))
                 )(Async[F].pure)
               )
      uninit <- StreamableHttpMcpClient.fromHttpClient[F](
                  httpClient, endpoint, extraHeaders, openSseStream, handler
                )
      client <- Resource.eval(uninit.initialize(info0, capabilities, protocolVersion))
    yield client

object HttpClientBuilder:

  def empty[F[_]: Async: LoggerFactory](httpClient: Client[F], endpoint: Uri): HttpClientBuilder[F] =
    new HttpClientBuilder[F](
      httpClient = httpClient,
      endpoint = endpoint,
      extraHeaders = Headers.empty,
      openSseStream = true,
      info = None,
      capabilities = ClientCapabilities.empty,
      protocolVersion = McpProtocol.DefaultVersion,
      handler = ClientHandler.noop[F]
    )
