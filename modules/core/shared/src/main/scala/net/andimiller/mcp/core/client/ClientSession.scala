package net.andimiller.mcp.core.client

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Supervisor
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.transport.MessageChannel

import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json
import io.circe.syntax.*

/** Drives the client-side message loop over a [[MessageChannel]].
  *
  * Mirror of [[net.andimiller.mcp.core.server.ServerSession]]. Spawns a background fiber that reads incoming messages
  * and dispatches:
  *   - [[Message.Response]] → [[ClientRequester.completeResponse]]
  *   - [[Message.Notification]] → published to an internal [[Topic]] (exposed as `McpClient.notifications`)
  *   - [[Message.Request]] → routed to the supplied [[ClientHandler]], whose result is sent back over the channel
  *
  * The returned [[UninitializedMcpClient]] performs the JSON-RPC handshake on demand and yields a fully-typed
  * [[McpClient]].
  */
object ClientSession:

  /** Configuration knobs for the session loop.
    *
    * @param maxConcurrent
    *   Upper bound on inbound JSON-RPC messages processed in parallel by the session's `parEvalMapUnordered`. Must be ≥
    *   2 to avoid the inbound-reader deadlock when a [[ClientHandler]] awaits a follow-up response (or when an
    *   in-flight client-initiated request awaits its response while a server-initiated request is being handled) — the
    *   awaited response is itself an inbound message.
    * @param notificationBufferSize
    *   Per-subscriber buffer size used when [[McpClient.notifications]] subscribes to the internal notification
    *   [[fs2.concurrent.Topic]]. Slow subscribers may drop or back-pressure beyond this many queued notifications.
    */
  final case class Config(
      maxConcurrent: Int = 16,
      notificationBufferSize: Int = 64
  )

  object Config:

    val default: Config = Config()

  /** Build a session over an arbitrary [[MessageChannel]] with a no-op [[ClientHandler]]. */
  def resource[F[_]: Async](
      channel: MessageChannel[F]
  ): Resource[F, UninitializedMcpClient[F]] =
    resource(channel, ClientHandler.noop[F], Config.default)

  /** Build a session over an arbitrary [[MessageChannel]] with the given handler. The fiber that reads the channel runs
    * in a [[Supervisor]]; when the resource is released it is cancelled and the channel closed.
    */
  def resource[F[_]: Async](
      channel: MessageChannel[F],
      handler: ClientHandler[F],
      config: Config = Config.default
  ): Resource[F, UninitializedMcpClient[F]] =
    for
      supervisor <- Supervisor[F]
      topic      <- Resource.eval(Topic[F, Message.Notification])
      requester  <- Resource.eval(ClientRequester.create[F](channel.send))
      _          <- Resource.eval(
             supervisor.supervise(loop(channel, requester, handler, topic, config).compile.drain)
           )
      _ <- Resource.onFinalize(channel.close.attempt.void)
    yield new UninitializedMcpClient[F]:
      def initialize(
          info: Implementation,
          capabilities: ClientCapabilities,
          protocolVersion: String
      ): F[McpClient[F]] =
        doInitialize(channel, requester, topic, info, capabilities, protocolVersion, config)

  // ── Internals ──────────────────────────────────────────────────────────

  private def loop[F[_]: Async](
      channel: MessageChannel[F],
      requester: ClientRequester[F],
      handler: ClientHandler[F],
      topic: Topic[F, Message.Notification],
      config: Config
  ): Stream[F, Unit] =
    channel.incoming.parEvalMapUnordered(config.maxConcurrent) {
      case Message.Response(_, id, result, error) =>
        val resolved: Either[JsonRpcError, Json] = error match
          case Some(e) => Left(e)
          case None    => Right(result.getOrElse(Json.obj()))
        requester.completeResponse(id, resolved)

      case n: Message.Notification =>
        handler.handleNotification(n.method, n.params).attempt.void *> topic.publish1(n).void

      case Message.Request(_, id, method, params) =>
        ClientHandler.respond(handler, method, id, params).flatMap(channel.send)
    }

  private def doInitialize[F[_]: Async](
      channel: MessageChannel[F],
      requester: ClientRequester[F],
      topic: Topic[F, Message.Notification],
      info: Implementation,
      capabilities: ClientCapabilities,
      protocolVersion: String,
      config: Config
  ): F[McpClient[F]] =
    val initRequest = InitializeRequest(
      protocolVersion = protocolVersion,
      capabilities = capabilities,
      clientInfo = info
    )
    requester
      .request("initialize", Some(initRequest.asJson))
      .flatMap {
        case Left(err)  => Async[F].raiseError(InitializeFailedException(err))
        case Right(raw) =>
          raw.as[InitializeResponse] match
            case Left(decode)    => Async[F].raiseError(InitializeFailedException.decode(decode))
            case Right(response) =>
              for
                _ <- requester.setServerCapabilities(response.capabilities)
                _ <- channel.send(Message.notification("notifications/initialized", None))
              yield mkInitialized(requester, topic, response, config.notificationBufferSize)
      }

  private def mkInitialized[F[_]: Async](
      requester: ClientRequester[F],
      topic: Topic[F, Message.Notification],
      response: InitializeResponse,
      notificationBufferSize: Int
  ): McpClient[F] =
    new McpClient[F]:
      val serverInfo: Implementation             = response.serverInfo
      val serverCapabilities: ServerCapabilities = response.capabilities
      val protocolVersion: String                = response.protocolVersion

      def ping(): F[Unit] =
        call("ping", None, _ => Async[F].unit)

      def listTools(cursor: Option[String]): F[ListToolsResponse] =
        call("tools/list", Some(ListToolsRequest(cursor).asJson), decodeAs[ListToolsResponse])

      def callTool(request: CallToolRequest): F[CallToolResponse] =
        call("tools/call", Some(request.asJson), decodeAs[CallToolResponse])

      def callTool(name: String, arguments: Json): F[CallToolResponse] =
        callTool(CallToolRequest(name, arguments))

      def listResources(cursor: Option[String]): F[ListResourcesResponse] =
        call("resources/list", Some(ListResourcesRequest(cursor).asJson), decodeAs[ListResourcesResponse])

      def readResource(uri: String): F[ReadResourceResponse] =
        call("resources/read", Some(ReadResourceRequest(uri).asJson), decodeAs[ReadResourceResponse])

      def listResourceTemplates(cursor: Option[String]): F[ListResourceTemplatesResponse] =
        call(
          "resources/templates/list",
          Some(ListResourceTemplatesRequest(cursor).asJson),
          decodeAs[ListResourceTemplatesResponse]
        )

      def subscribe(uri: String): F[Unit] =
        call("resources/subscribe", Some(SubscribeRequest(uri).asJson), _ => Async[F].unit)

      def unsubscribe(uri: String): F[Unit] =
        call("resources/unsubscribe", Some(UnsubscribeRequest(uri).asJson), _ => Async[F].unit)

      def listPrompts(cursor: Option[String]): F[ListPromptsResponse] =
        call("prompts/list", Some(ListPromptsRequest(cursor).asJson), decodeAs[ListPromptsResponse])

      def getPrompt(name: String, arguments: Map[String, Json]): F[GetPromptResponse] =
        call("prompts/get", Some(GetPromptRequest(name, arguments).asJson), decodeAs[GetPromptResponse])

      def notifications: Stream[F, Message.Notification] =
        topic.subscribe(notificationBufferSize)

      private def call[A](method: String, params: Option[Json], decode: Json => F[A]): F[A] =
        requester.request(method, params, None).flatMap {
          case Right(json) => decode(json)
          case Left(err)   => Async[F].raiseError(McpRemoteException(method, err))
        }

      private def decodeAs[A](using io.circe.Decoder[A]): Json => F[A] =
        json => Async[F].fromEither(json.as[A].leftMap(McpDecodeException(json, _)))

  /** Raised when the server returns an error to a client request. */
  final case class McpRemoteException(method: String, error: JsonRpcError)
      extends RuntimeException(s"$method failed: [${error.code}] ${error.message}")

  /** Raised when a successful response can't be decoded into the expected case class. */
  final case class McpDecodeException(json: Json, cause: io.circe.DecodingFailure)
      extends RuntimeException(s"failed to decode response: ${cause.getMessage}\n  body: ${json.noSpaces}", cause)

  /** Raised when the `initialize` handshake fails. */
  final case class InitializeFailedException(reason: String, cause: Option[Throwable] = None)
      extends RuntimeException(reason, cause.orNull)

  object InitializeFailedException:

    def apply(err: JsonRpcError): InitializeFailedException =
      InitializeFailedException(s"initialize failed: [${err.code}] ${err.message}")

    def decode(err: io.circe.DecodingFailure): InitializeFailedException =
      InitializeFailedException(s"could not decode InitializeResponse: ${err.getMessage}", Some(err))
