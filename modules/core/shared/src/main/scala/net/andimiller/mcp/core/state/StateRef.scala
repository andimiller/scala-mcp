package net.andimiller.mcp.core.state

import cats.Functor
import cats.effect.kernel.Ref

/**
 * A `Ref`-like abstraction for state that can be backed by in-memory or external storage (e.g. Redis).
 *
 * Note: For external implementations, `update` and `modify` are NOT atomic across servers
 * (they perform read-then-write). With session affinity this is safe.
 */
trait StateRef[F[_], A]:
  def get: F[A]
  def set(a: A): F[Unit]
  def update(f: A => A): F[Unit]
  def modify[B](f: A => (A, B)): F[B]

object StateRef:

  /** Lift a cats-effect Ref into a StateRef. */
  def fromRef[F[_]: Functor, A](ref: Ref[F, A]): StateRef[F, A] =
    new StateRef[F, A]:
      def get: F[A] = ref.get
      def set(a: A): F[Unit] = ref.set(a)
      def update(f: A => A): F[Unit] = ref.update(f)
      def modify[B](f: A => (A, B)): F[B] = ref.modify(f)
