package net.andimiller.mcp.core.client

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.ServerCapabilities
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId
import net.andimiller.mcp.core.transport.MessageRequester

import io.circe.Json

/** Send client-initiated JSON-RPC requests and await correlated responses.
  *
  * Symmetric counterpart to [[net.andimiller.mcp.core.server.ServerRequester]]: thin wrapper over a shared
  * [[MessageRequester]] (id prefix `"cli-"`) plus client-only state for negotiated [[ServerCapabilities]].
  */
trait ClientRequester[F[_]]:

  /** Send a request, await the matched response or a timeout. */
  def request(
      method: String,
      params: Option[Json],
      timeout: Option[FiniteDuration] = None
  ): F[Either[JsonRpcError, Json]]

  /** Negotiated server capabilities (None until `initialize` completes). */
  def serverCapabilities: F[Option[ServerCapabilities]]

  /** Complete a pending request with the response payload. Called by the client session's read loop. */
  def completeResponse(id: RequestId, result: Either[JsonRpcError, Json]): F[Unit]

  /** Set the negotiated server capabilities once `initialize` returns. */
  def setServerCapabilities(caps: ServerCapabilities): F[Unit]

object ClientRequester:

  /** Marker error code used when the request times out before a response arrives. */
  val TimeoutErrorCode: Int = MessageRequester.TimeoutErrorCode

  private val IdPrefix: String = "cli-"

  /** Build a requester that publishes outbound messages via `publish`. */
  def create[F[_]: Async](publish: Message => F[Unit]): F[ClientRequester[F]] =
    for
      caps      <- Ref[F].of(Option.empty[ServerCapabilities])
      requester <- MessageRequester.create[F](publish, IdPrefix)
    yield new ClientRequester[F]:
      def serverCapabilities: F[Option[ServerCapabilities]]                              = caps.get
      def setServerCapabilities(c: ServerCapabilities): F[Unit]                          = caps.set(Some(c))
      def request(method: String, params: Option[Json], timeout: Option[FiniteDuration]) =
        requester.request(method, params, timeout)
      def completeResponse(id: RequestId, result: Either[JsonRpcError, Json]) =
        requester.completeResponse(id, result)
