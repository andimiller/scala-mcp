package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.{JsonRpcError, Message, RequestId}

/**
 * Pure request→response message handler extracted from ServerSession.
 *
 * Given a JSON-RPC [[Message]], produces an optional response:
 *  - Requests  → `Some(responseMessage)` (or `None` if cancelled and no error response should be sent)
 *  - Notifications → `None`
 *  - Responses from client → `None` (ignored)
 *
 * Wires in a [[NotificationSink]] so tool handlers can emit `notifications/progress` and a
 * [[CancellationRegistry]] so `notifications/cancelled` can interrupt in-flight requests.
 *
 * This is transport-agnostic and can be shared between stdio and HTTP transports.
 */
class RequestHandler[F[_]: Async](
  server: Server[F],
  notificationSink: NotificationSink[F],
  cancellation: CancellationRegistry[F]
):

  /** Cancellation error code (LSP convention, adopted by several MCP implementations). */
  val CancelledErrorCode: Int = -32800

  /** Convenience constructor for transports that don't need progress or cancellation. */
  def this(server: Server[F]) =
    this(server, NotificationSink.noop[F], CancellationRegistry.noop[F])

  /**
   * Handle a single incoming message and optionally produce a response.
   */
  def handle(message: Message): F[Option[Message]] = message match
    case Message.Request(_, id, method, params) =>
      handleRequest(id, method, params).map(Some(_))

    case Message.Notification(_, method, params) =>
      handleNotification(method, params)
        .as(None)
        .handleErrorWith(_ => None.pure[F])

    case Message.Response(_, _, _, _) =>
      None.pure[F]

  /**
   * Handle a JSON-RPC request and return a response message.
   *
   * Race the handler's work against the request's cancellation signal so that
   * `notifications/cancelled` can interrupt long-running work.
   */
  private def handleRequest(id: RequestId, method: String, params: Option[Json]): F[Message] =
    cancellation.register(id).flatMap { cancelSignal =>
      val progressToken = extractProgressToken(params)
      val rc            = RequestContext.live(id, progressToken, notificationSink, cancelSignal)

      val work: F[Message] =
        dispatch(id, method, params, rc)
          .map(json => Message.response(id, json))
          .handleError(error => Message.errorResponse(id, JsonRpcError.internalError(Option(error.getMessage).getOrElse(error.toString))))

      Async[F].race(work, cancelSignal.get).map {
        case Left(msg)   => msg
        case Right(())   => Message.errorResponse(id, JsonRpcError(CancelledErrorCode, "Request cancelled"))
      }.guarantee(cancellation.complete(id))
    }

  private def dispatch(id: RequestId, method: String, params: Option[Json], rc: RequestContext[F]): F[Json] =
    method match
      case "initialize" =>
        handleInitialize(params)

      case "ping" =>
        server.ping().as(Json.obj())

      case "tools/list" =>
        val request = params.flatMap(_.as[ListToolsRequest].toOption).getOrElse(ListToolsRequest())
        server.listTools(request).map(_.asJson)

      case "tools/call" =>
        params.flatMap(_.as[CallToolRequest].toOption) match
          case Some(request) =>
            server.callTool(request, rc).map(_.asJson)
          case None =>
            Async[F].raiseError(new Exception("Missing or invalid tool call request"))

      case "resources/list" =>
        val request = params.flatMap(_.as[ListResourcesRequest].toOption).getOrElse(ListResourcesRequest())
        server.listResources(request).map(_.asJson)

      case "resources/read" =>
        params.flatMap(_.as[ReadResourceRequest].toOption) match
          case Some(request) =>
            server.readResource(request).map(_.asJson)
          case None =>
            Async[F].raiseError(new Exception("Missing or invalid resource read request"))

      case "resources/templates/list" =>
        val request = params.flatMap(_.as[ListResourceTemplatesRequest].toOption).getOrElse(ListResourceTemplatesRequest())
        server.listResourceTemplates(request).map(_.asJson)

      case "resources/subscribe" =>
        params.flatMap(_.as[SubscribeRequest].toOption) match
          case Some(request) =>
            server.subscribe(request).as(Json.obj())
          case None =>
            Async[F].raiseError(new Exception("Missing or invalid subscribe request"))

      case "resources/unsubscribe" =>
        params.flatMap(_.as[UnsubscribeRequest].toOption) match
          case Some(request) =>
            server.unsubscribe(request).as(Json.obj())
          case None =>
            Async[F].raiseError(new Exception("Missing or invalid unsubscribe request"))

      case "prompts/list" =>
        val request = params.flatMap(_.as[ListPromptsRequest].toOption).getOrElse(ListPromptsRequest())
        server.listPrompts(request).map(_.asJson)

      case "prompts/get" =>
        params.flatMap(_.as[GetPromptRequest].toOption) match
          case Some(request) =>
            server.getPrompt(request).map(_.asJson)
          case None =>
            Async[F].raiseError(new Exception("Missing or invalid prompt get request"))

      case unknown =>
        Async[F].raiseError(new Exception(s"Unknown method: $unknown"))

  /**
   * Handle the initialize request.
   */
  private def handleInitialize(params: Option[Json]): F[Json] =
    params.flatMap(_.as[InitializeRequest].toOption) match
      case Some(_) =>
        val response = InitializeResponse(
          protocolVersion = RequestHandler.ProtocolVersion,
          capabilities = server.capabilities,
          serverInfo = server.info
        )
        response.asJson.pure[F]
      case None =>
        Async[F].raiseError(new Exception("Missing or invalid initialize request"))

  /**
   * Handle a notification (no response expected).
   */
  private def handleNotification(method: String, params: Option[Json]): F[Unit] =
    method match
      case "notifications/initialized" =>
        Async[F].unit

      case "notifications/cancelled" =>
        params.flatMap(extractCancelRequestId) match
          case Some(id) => cancellation.cancel(id)
          case None     => Async[F].unit

      case _ =>
        Async[F].unit

  /** Extract the progressToken from `params._meta.progressToken`, if present. */
  private def extractProgressToken(params: Option[Json]): Option[ProgressToken] =
    params
      .flatMap(_.hcursor.downField("_meta").downField("progressToken").focus)
      .flatMap(json => json.as[ProgressToken].toOption)

  /** Extract the requestId from a `notifications/cancelled` params object. */
  private def extractCancelRequestId(params: Json): Option[RequestId] =
    params.hcursor.downField("requestId").as[RequestId].toOption

object RequestHandler:
  val ProtocolVersion: String = "2025-11-25"
