package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import io.circe.Json
import io.circe.syntax.*
import org.typelevel.log4cats.LoggerFactory

/** Pure request→response message handler extracted from ServerSession.
  *
  * Given a JSON-RPC [[Message]], produces an optional response:
  *   - Requests → `Some(responseMessage)`
  *   - Notifications → `None`
  *   - Responses from client → routed to [[ServerRequester.completeResponse]], no outbound message
  *
  * This is transport-agnostic and can be shared between stdio and HTTP transports.
  */
class RequestHandler[F[_]: Async: LoggerFactory](
    sessionId: String,
    server: Server[F],
    requester: ServerRequester[F],
    cancellation: CancellationRegistry[F]
):

  private val logger = LoggerFactory[F].getLoggerFromName("net.andimiller.mcp.core.server.RequestHandler")

  /** Methods that bypass the cancellation registry. `initialize` MUST NOT be cancelled per spec (MCP 2025-11-25);
    * `ping` is trivial and used for liveness, so cancelling it is pointless.
    */
  private def isUncancellable(method: String): Boolean =
    method === "initialize" || method === "ping"

  /** Handle a single incoming message and optionally produce a response. */
  def handle(message: Message): F[Option[Message]] = message match
    case Message.Request(_, id, method, params) =>
      val dispatched =
        if isUncancellable(method) then handleRequest(id, method, params).map(Some(_))
        else cancellation.track(id)(handleRequest(id, method, params))

      dispatched.handleErrorWith { error =>
        logger.error(
          Map("sessionId" -> sessionId, "requestId" -> id.toString, "method" -> method),
          error
        )("JSON-RPC request handler raised") *>
          Some(Message.errorResponse(id, JsonRpcError.internalError(error.getMessage))).pure[F]
      }

    case Message.Notification(_, method, params) =>
      handleNotification(method, params)
        .as(None)
        .handleErrorWith { error =>
          logger.error(Map("sessionId" -> sessionId, "method" -> method), error)("Notification handler raised") *>
            None.pure[F]
        }

    case Message.Response(_, id, result, error) =>
      val resolved: Either[JsonRpcError, Json] = error match
        case Some(e) => Left(e)
        case None    => Right(result.getOrElse(Json.obj()))
      requester.completeResponse(id, resolved).as(None)

  /** Build the structured-log context for a request. Tool name, resource URI, and prompt name are pulled from `params`
    * (when present) so operators can see *what* was called without us logging the full arguments — which may contain
    * user input.
    */
  private def requestLogFields(id: RequestId, method: String, params: Option[Json]): Map[String, String] =
    val base   = Map("sessionId" -> sessionId, "requestId" -> id.toString, "method" -> method)
    val cursor = params.map(_.hcursor)
    method match
      case "tools/call" =>
        cursor.flatMap(_.downField("name").as[String].toOption).fold(base)(n => base + ("tool" -> n))
      case "resources/read" | "resources/subscribe" | "resources/unsubscribe" =>
        cursor.flatMap(_.downField("uri").as[String].toOption).fold(base)(u => base + ("uri" -> u))
      case "prompts/get" =>
        cursor.flatMap(_.downField("name").as[String].toOption).fold(base)(n => base + ("prompt" -> n))
      case _ => base

  /** Handle a JSON-RPC request and return a response message. */
  private def handleRequest(id: RequestId, method: String, params: Option[Json]): F[Message] =
    val logFields = requestLogFields(id, method, params)
    val result    = logger.info(logFields)("dispatching request") *> (method match
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
            server.callTool(request).map(_.asJson)
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
        val request =
          params.flatMap(_.as[ListResourceTemplatesRequest].toOption).getOrElse(ListResourceTemplatesRequest())
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
        Async[F].raiseError(new Exception(s"Unknown method: $unknown")))

    result.map(json => Message.response(id, json)).handleErrorWith { error =>
      logger.error(logFields, error)("JSON-RPC method dispatch failed") *>
        Message.errorResponse(id, JsonRpcError.internalError(error.getMessage)).pure[F]
    }

  /** Handle the initialize request. */
  private def handleInitialize(params: Option[Json]): F[Json] =
    params.flatMap(_.as[InitializeRequest].toOption) match
      case Some(request) =>
        val response = InitializeResponse(
          protocolVersion = "2025-11-25",
          capabilities = server.capabilities,
          serverInfo = server.info
        )
        requester.setClientCapabilities(request.capabilities).as(response.asJson)
      case None =>
        Async[F].raiseError(new Exception("Missing or invalid initialize request"))

  /** Handle a notification (no response expected). */
  private def handleNotification(method: String, params: Option[Json]): F[Unit] =
    method match
      case "notifications/initialized" =>
        Async[F].unit

      case "notifications/cancelled" =>
        params.flatMap(_.as[CancelledNotificationParams].toOption) match
          case Some(p) => cancellation.cancel(p.requestId)
          case None    => Async[F].unit

      case _ =>
        Async[F].unit
