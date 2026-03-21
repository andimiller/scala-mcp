package net.andimiller.mcp.core.server

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*
import io.circe.Json
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.transport.MessageChannel

/**
 * Core MCP server interface.
 *
 * A server provides tools, resources, and prompts to clients.
 */
trait Server[F[_]]:
  /** Server implementation information */
  def info: Implementation

  /** Server capabilities */
  def capabilities: ServerCapabilities

  /** List all available tools */
  def listTools(request: ListToolsRequest): F[ListToolsResponse]

  /** Call a tool */
  def callTool(request: CallToolRequest): F[CallToolResponse]

  /** List all available resources */
  def listResources(request: ListResourcesRequest): F[ListResourcesResponse]

  /** Read a resource */
  def readResource(request: ReadResourceRequest): F[ReadResourceResponse]

  /** Subscribe to resource updates */
  def subscribe(request: SubscribeRequest): F[Unit]

  /** Unsubscribe from resource updates */
  def unsubscribe(request: UnsubscribeRequest): F[Unit]

  /** List all available prompts */
  def listPrompts(request: ListPromptsRequest): F[ListPromptsResponse]

  /** Get a prompt */
  def getPrompt(request: GetPromptRequest): F[GetPromptResponse]

  /** Handle ping request */
  def ping(): F[Unit]

/**
 * Server session that processes messages over a transport channel.
 */
class ServerSession[F[_]: Async](
  server: Server[F],
  channel: MessageChannel[F]
):
  import cats.effect.syntax.all.*
  import net.andimiller.mcp.core.codecs.CirceCodecs.given
  import io.circe.syntax.*
  import io.circe.parser.decode
  import net.andimiller.mcp.core.protocol.jsonrpc.{RequestId, JsonRpcError, ErrorCode}

  /**
   * Run the server session, processing incoming messages until the channel closes.
   */
  def run: F[Unit] =
    channel.incoming
      .evalMap(handleMessage)
      .compile
      .drain

  /**
   * Handle a single incoming message.
   */
  private def handleMessage(message: Message): F[Unit] = message match
    case Message.Request(_, id, method, params) =>
      handleRequest(id, method, params)
        .flatMap(response => channel.send(response))
        .handleErrorWith { error =>
          val errorResponse = Message.errorResponse(
            id,
            JsonRpcError.internalError(error.getMessage)
          )
          channel.send(errorResponse)
        }

    case Message.Notification(_, method, params) =>
      handleNotification(method, params)
        .handleErrorWith(_ => Async[F].unit) // Notifications don't send responses

    case Message.Response(_, _, _, _) =>
      // Client shouldn't send responses to us, but we'll ignore them
      Async[F].unit

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
        // Client has finished initialization
        Async[F].unit

      case "notifications/cancelled" =>
        // Client cancelled a request (we don't track cancellation yet)
        Async[F].unit

      case _ =>
        // Unknown notification, ignore
        Async[F].unit
