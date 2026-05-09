package net.andimiller.mcp.core.client

import cats.Applicative
import cats.effect.kernel.Async
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import io.circe.Json

/** Handles server-initiated JSON-RPC requests received by the client.
  *
  * The MCP spec lets servers call back to clients for capabilities the client advertises during `initialize`:
  *   - `sampling/createMessage` — server asks the client (typically an LLM host) to run a completion
  *   - `elicitation/create` — server asks the client to gather input from the user
  *   - `roots/list` — server asks for the list of filesystem roots the client exposes
  *
  * Servers may also send notifications (handled separately by `McpClient.notifications`) — this trait is for *requests*
  * that need a response.
  *
  * Default implementation [[noop]] returns `MethodNotFound` for every request, which is the correct behaviour for a
  * client that didn't advertise the corresponding capability.
  */
trait ClientHandler[F[_]]:

  /** Handle a server-initiated request. Return either a JSON result or an error; the framework wraps it into a
    * `Message.Response` and sends it back over the channel.
    */
  def handle(method: String, id: RequestId, params: Option[Json]): F[Either[JsonRpcError, Json]]

  /** Handle a server-initiated notification. The default ignores it; override to react to e.g.
    * `notifications/tools/list_changed`. Notifications are also surfaced via `McpClient.notifications` for stream-style
    * consumption — overriding here is for handlers that prefer the callback style.
    */
  def handleNotification(method: String, params: Option[Json]): F[Unit]

object ClientHandler:

  /** A handler that rejects every server-initiated request with `MethodNotFound` and ignores notifications. */
  def noop[F[_]: Applicative]: ClientHandler[F] = new ClientHandler[F]:
    def handle(method: String, id: RequestId, params: Option[Json]): F[Either[JsonRpcError, Json]] =
      Applicative[F].pure(Left(JsonRpcError.methodNotFound(method)))
    def handleNotification(method: String, params: Option[Json]): F[Unit] =
      Applicative[F].unit

  /** Build a handler from a partial function over method names. Anything not covered falls back to `MethodNotFound`. */
  def of[F[_]: Async](
      requests: PartialFunction[String, (RequestId, Option[Json]) => F[Either[JsonRpcError, Json]]] =
        PartialFunction.empty,
      notifications: PartialFunction[String, Option[Json] => F[Unit]] = PartialFunction.empty
  ): ClientHandler[F] = new ClientHandler[F]:
    def handle(method: String, id: RequestId, params: Option[Json]): F[Either[JsonRpcError, Json]] =
      requests.lift(method) match
        case Some(f) => f(id, params)
        case None    => Async[F].pure(Left(JsonRpcError.methodNotFound(method)))
    def handleNotification(method: String, params: Option[Json]): F[Unit] =
      notifications.lift(method).fold(Async[F].unit)(_(params))

  /** Wrap a `ClientHandler` invocation into a complete `Message.Response`. */
  private[client] def respond[F[_]: Async](
      handler: ClientHandler[F],
      method: String,
      id: RequestId,
      params: Option[Json]
  ): F[Message] =
    handler
      .handle(method, id, params)
      .map {
        case Right(json) => Message.response(id, json)
        case Left(err)   => Message.errorResponse(id, err)
      }
      .handleErrorWith { t =>
        Message.errorResponse(id, JsonRpcError.internalError(t.getMessage)).pure[F]
      }
