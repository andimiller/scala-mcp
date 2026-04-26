package net.andimiller.mcp.core.server

import cats.effect.kernel.{Concurrent, Deferred, Ref}
import cats.syntax.all.*
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

/**
 * Tracks in-flight requests so that `notifications/cancelled` messages can signal the
 * corresponding handler to stop.
 *
 * Each registered request holds a [[Deferred]] that the handler races against its own work.
 * Completing the Deferred wins the race and causes the request's work fiber to be cancelled
 * by cats-effect's structured concurrency.
 *
 * In distributed deployments (e.g. Redis-backed), `register`, `complete`, and `isActive`
 * remain local — they describe only the fibers running on THIS node. `cancel` can be routed
 * across nodes by the implementation (e.g. via pub/sub); see `RedisCancellationRegistry`.
 */
trait CancellationRegistry[F[_]]:

  /** Register a new request id and return the cancellation signal for it. */
  def register(id: RequestId): F[Deferred[F, Unit]]

  /** Remove a request id once its handler has completed normally. Local-only. */
  def complete(id: RequestId): F[Unit]

  /**
   * Signal cancellation for the given id. In the in-memory impl: completes the Deferred if
   * present locally, no-op otherwise. In distributed impls: broadcasts the cancel to all
   * nodes — the node actually running the handler (which may be a different node) completes
   * its local Deferred. The return value reflects "publish/broadcast succeeded", not
   * "a handler was cancelled"; callers cannot reliably know whether any fiber was interrupted.
   */
  def cancel(id: RequestId): F[Unit]

  /**
   * Whether a request id is currently registered locally. In distributed deployments this
   * reflects only the fibers running on this node — other nodes' active fibers are invisible.
   */
  def isActive(id: RequestId): F[Boolean]

object CancellationRegistry:

  def create[F[_]: Concurrent]: F[CancellationRegistry[F]] =
    Ref.of[F, Map[RequestIdKey, Deferred[F, Unit]]](Map.empty).map { ref =>
      new CancellationRegistry[F]:

        def register(id: RequestId): F[Deferred[F, Unit]] =
          for
            deferred <- Deferred[F, Unit]
            _        <- ref.update(_ + (key(id) -> deferred))
          yield deferred

        def complete(id: RequestId): F[Unit] =
          ref.update(_ - key(id))

        def cancel(id: RequestId): F[Unit] =
          ref.modify { map =>
            val k = key(id)
            map.get(k) match
              case Some(deferred) => (map - k, deferred.complete(()).void)
              case None           => (map, Concurrent[F].unit)
          }.flatten

        def isActive(id: RequestId): F[Boolean] =
          ref.get.map(_.contains(key(id)))
    }

  /** No-op registry; useful for transports or tests that don't support cancellation. */
  def noop[F[_]: Concurrent]: CancellationRegistry[F] =
    new CancellationRegistry[F]:
      def register(id: RequestId): F[Deferred[F, Unit]] = Deferred[F, Unit]
      def complete(id: RequestId): F[Unit]              = Concurrent[F].unit
      def cancel(id: RequestId): F[Unit]                = Concurrent[F].unit
      def isActive(id: RequestId): F[Boolean]           = Concurrent[F].pure(false)

  /**
   * RequestId is an opaque type (String | Long); we normalize it to a comparable key so that
   * the Map behaves correctly across JS/Native where String|Long may be boxed differently.
   */
  private type RequestIdKey = String

  private def key(id: RequestId): RequestIdKey =
    id.value match
      case s: String => s"s:$s"
      case l: Long   => s"l:$l"
