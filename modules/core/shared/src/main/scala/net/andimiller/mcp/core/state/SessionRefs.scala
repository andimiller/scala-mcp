package net.andimiller.mcp.core.state

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*

import io.circe.Decoder
import io.circe.Encoder

/** Factory for creating session-scoped `StateRef` instances.
  *
  * The `name` parameter uniquely identifies a piece of state within a session. For external backends (e.g. Redis), this
  * becomes part of the storage key.
  *
  * The `Encoder`/`Decoder` constraints are required by external backends for serialization but ignored by the in-memory
  * implementation.
  */
trait SessionRefs[F[_]]:

  def ref[A: Encoder: Decoder](name: String, initial: A): F[StateRef[F, A]]

object SessionRefs:

  /** In-memory implementation — ignores Encoder/Decoder, uses Ref internally. */
  def inMemory[F[_]: Async]: SessionRefs[F] =
    new SessionRefs[F]:
      def ref[A: Encoder: Decoder](name: String, initial: A): F[StateRef[F, A]] =
        Ref.of[F, A](initial).map(StateRef.fromRef[F, A])
