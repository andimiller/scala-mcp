package net.andimiller.mcp.core.server

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.ClientCapabilities
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId
import net.andimiller.mcp.core.transport.MessageRequester

import io.circe.Json

/** Send server-initiated JSON-RPC requests to the client and await correlated responses.
  *
  * Thin wrapper over a shared [[MessageRequester]] (id prefix `"srv-"`) plus server-only state for negotiated
  * [[ClientCapabilities]]. The publish callback is shared with [[NotificationSink]] (both write to the same outbound
  * channel — fs2 Topic on stdio/HTTP).
  *
  * Negotiated [[ClientCapabilities]] are stored here so feature-specific clients (e.g. [[ElicitationClient]]) can
  * capability-check before issuing a request.
  */
trait ServerRequester[F[_]]:

  /** Send a request, await the matched response or a timeout. */
  def request(
      method: String,
      params: Option[Json],
      timeout: Option[FiniteDuration] = None
  ): F[Either[JsonRpcError, Json]]

  /** Negotiated client capabilities (None until `initialize` completes). */
  def clientCapabilities: F[Option[ClientCapabilities]]

  /** Complete a pending request with the response payload. Called by the request handler. */
  def completeResponse(id: RequestId, result: Either[JsonRpcError, Json]): F[Unit]

  /** Set the negotiated client capabilities once `initialize` is processed. */
  def setClientCapabilities(caps: ClientCapabilities): F[Unit]

object ServerRequester:

  /** Marker error code used when the request times out before a response arrives. */
  val TimeoutErrorCode: Int = MessageRequester.TimeoutErrorCode

  /** Id prefix used for server-initiated requests. */
  private val IdPrefix: String = "srv-"

  /** Build a requester that publishes outbound messages via `publish`. */
  def create[F[_]: Async](publish: Message => F[Unit]): F[ServerRequester[F]] =
    for
      caps      <- Ref[F].of(Option.empty[ClientCapabilities])
      requester <- MessageRequester.create[F](publish, IdPrefix)
    yield new ServerRequester[F]:
      def clientCapabilities: F[Option[ClientCapabilities]]                              = caps.get
      def setClientCapabilities(c: ClientCapabilities): F[Unit]                          = caps.set(Some(c))
      def request(method: String, params: Option[Json], timeout: Option[FiniteDuration]) =
        requester.request(method, params, timeout)
      def completeResponse(id: RequestId, result: Either[JsonRpcError, Json]) =
        requester.completeResponse(id, result)

  /** A no-op requester for transports/tests that don't expose server-initiated requests. */
  def noop[F[_]: Async]: F[ServerRequester[F]] =
    for
      caps      <- Ref[F].of(Option.empty[ClientCapabilities])
      requester <- MessageRequester.noop[F]("ServerRequester not configured")
    yield new ServerRequester[F]:
      def request(method: String, params: Option[Json], timeout: Option[FiniteDuration]) =
        requester.request(method, params, timeout)
      def clientCapabilities                                                  = caps.get
      def completeResponse(id: RequestId, result: Either[JsonRpcError, Json]) = requester.completeResponse(id, result)
      def setClientCapabilities(c: ClientCapabilities)                        = caps.set(Some(c))
