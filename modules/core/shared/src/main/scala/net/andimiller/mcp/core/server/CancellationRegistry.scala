package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.syntax.all.*
import cats.syntax.all.*
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

/** Per-session registry of in-flight requests that supports cooperative cancellation.
  *
  * Implements MCP `notifications/cancelled` semantics: a `Deferred[F, Unit]` is registered for each tracked request and
  * raced against the request's work. Completing the deferred signals cancellation; `Async.race` cancels the loser
  * fiber, so the work fiber stops and `track` returns `None` — the caller suppresses the JSON-RPC response, per spec.
  */
trait CancellationRegistry[F[_]]:

  /** Register `id`, run `work`, and race against an external cancel signal.
    *
    * Returns `Some(value)` if `work` completed first, `None` if `cancel(id)` (or `cancelAll`) fired first. Errors from
    * `work` propagate. The registry entry is cleaned up in all cases.
    */
  def track[A](id: RequestId)(work: F[A]): F[Option[A]]

  /** Signal cancel for `id`. No-op if not registered (already completed or never seen). */
  def cancel(id: RequestId): F[Unit]

  /** Cancel every currently-tracked id. Used on session teardown. */
  def cancelAll: F[Unit]

object CancellationRegistry:

  def create[F[_]: Async]: F[CancellationRegistry[F]] =
    Ref[F].of(Map.empty[RequestId, Deferred[F, Unit]]).map { ref =>
      new CancellationRegistry[F]:

        def track[A](id: RequestId)(work: F[A]): F[Option[A]] =
          for
            d      <- Deferred[F, Unit]
            _      <- ref.update(_.updated(id, d))
            result <- Async[F].race(d.get, work).guarantee(ref.update(_ - id))
          yield result match
            case Left(_)      => None
            case Right(value) => Some(value)

        def cancel(id: RequestId): F[Unit] =
          ref.modify(m => (m - id, m.get(id))).flatMap {
            case Some(d) => d.complete(()).void
            case None    => Async[F].unit
          }

        def cancelAll: F[Unit] =
          ref.getAndSet(Map.empty).flatMap { m =>
            m.values.toList.traverse_(_.complete(()).void)
          }
    }

  /** Pass-through registry: `track` runs work directly; `cancel` and `cancelAll` are no-ops. */
  def noop[F[_]: Async]: CancellationRegistry[F] =
    new CancellationRegistry[F]:
      def track[A](id: RequestId)(work: F[A]): F[Option[A]] = work.map(Some(_))
      def cancel(id: RequestId): F[Unit]                    = Async[F].unit
      def cancelAll: F[Unit]                                = Async[F].unit
