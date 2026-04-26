package net.andimiller.mcp.core.server

import cats.Applicative
import cats.effect.kernel.{Concurrent, Deferred}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

/**
 * Request-scoped context passed to tool handlers.
 *
 * Exposes:
 *  - the originating [[RequestId]] of the call
 *  - the optional [[ProgressToken]] supplied by the client in `params._meta.progressToken`
 *  - a [[progress]] emitter that sends `notifications/progress` tagged with that token
 *    (no-op when the client did not provide a token)
 *  - a [[cancelled]] signal that semantically blocks until the client has sent
 *    `notifications/cancelled` for this request — useful for racing against long work
 */
trait RequestContext[F[_]]:

  def requestId: RequestId

  def progressToken: Option[ProgressToken]

  /**
   * Emit a progress notification for this request.
   *
   * @param progress the current progress (required by spec; monotonically increasing)
   * @param total    optional total magnitude — if present, clients can render a percentage
   * @param message  optional human-readable status string
   */
  def progress(progress: Double, total: Option[Double] = None, message: Option[String] = None): F[Unit]

  /** Semantically blocks until this request has been cancelled. */
  def cancelled: F[Unit]

  /** Non-blocking check for whether cancellation has been signalled. */
  def isCancelled: F[Boolean]

object RequestContext:

  /**
   * Build a live RequestContext wired up to the given notification sink and cancellation
   * deferred. The deferred typically comes from [[CancellationRegistry.register]].
   */
  def live[F[_]: Concurrent](
    id: RequestId,
    token: Option[ProgressToken],
    sink: NotificationSink[F],
    cancelSignal: Deferred[F, Unit]
  ): RequestContext[F] =
    new RequestContext[F]:
      val requestId: RequestId            = id
      val progressToken: Option[ProgressToken] = token

      def progress(p: Double, total: Option[Double], message: Option[String]): F[Unit] =
        token match
          case None => Concurrent[F].unit
          case Some(t) =>
            val params = Json.obj(
              "progressToken" -> t.asJson,
              "progress"      -> p.asJson,
              "total"         -> total.asJson,
              "message"       -> message.asJson
            ).deepDropNullValues
            sink.notify("notifications/progress", params)

      val cancelled: F[Unit] = cancelSignal.get

      val isCancelled: F[Boolean] = cancelSignal.tryGet.map(_.isDefined)

  /**
   * A RequestContext that never emits progress and never signals cancellation.
   * Useful for tests and for call sites (e.g., in-process tool invocation) that don't
   * have a transport to emit notifications through.
   */
  def noop[F[_]: Concurrent](id: RequestId = RequestId.fromString("")): RequestContext[F] =
    new RequestContext[F]:
      val requestId: RequestId                 = id
      val progressToken: Option[ProgressToken] = None
      def progress(p: Double, total: Option[Double], message: Option[String]): F[Unit] =
        Applicative[F].unit
      val cancelled: F[Unit]                   = Concurrent[F].never
      val isCancelled: F[Boolean]              = Concurrent[F].pure(false)
