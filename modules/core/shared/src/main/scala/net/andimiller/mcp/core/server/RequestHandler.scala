package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
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
 *  - Requests  → `Some(responseMessage)`
 *  - Notifications → `None`
 *  - Responses from client → `None` (ignored)
 *
 * This is transport-agnostic and can be shared between stdio and HTTP transports.
 */
class RequestHandler[F[_]: Async](server: Server[F]):

  /**
   * Handle a single incoming message and optionally produce a response.
   */
  def handle(message: Message): F[Option[Message]] = message match
    case Message.Request(_, id, method, params) =>
      handleRequest(id, method, params)
        .map(Some(_))
        .handleErrorWith { error =>
          Some(Message.errorResponse(id, JsonRpcError.internalError(error.getMessage))).pure[F]
        }

    case Message.Notification(_, method, params) =>
      handleNotification(method, params)
        .as(None)
        .handleErrorWith(_ => None.pure[F])

    case Message.Response(_, _, _, _) =>
      None.pure[F]

  /**
   * Handle a JSON-RPC request and return a response message.
   */
  private def handleRequest(id: RequestId, method: String, params: Option[Json]): F[Message] =
    val result = method match
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

    result.map(json => Message.response(id, json))
      .handleErrorWith { error =>
        Message.errorResponse(id, JsonRpcError.internalError(error.getMessage)).pure[F]
      }

  /**
   * Handle the initialize request.
   */
  private def handleInitialize(params: Option[Json]): F[Json] =
    params.flatMap(_.as[InitializeRequest].toOption) match
      case Some(request) =>
        val response = InitializeResponse(
          protocolVersion = "2025-11-25",
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
        Async[F].unit

      case _ =>
        Async[F].unit
