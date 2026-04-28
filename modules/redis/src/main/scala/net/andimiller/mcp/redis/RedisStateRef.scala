package net.andimiller.mcp.redis

import cats.effect.kernel.Async
import cats.syntax.all.*
import dev.profunktor.redis4cats.RedisCommands
import io.circe.Decoder
import io.circe.Encoder
import io.circe.parser.decode
import io.circe.syntax.*
import net.andimiller.mcp.core.state.StateRef

import scala.concurrent.duration.FiniteDuration

/** A [[StateRef]] backed by a field in a Redis hash, with TTL on the hash key.
  *
  * All state for a session lives in a single hash at `hashKey`, with each piece of state stored as a separate field.
  *
  * '''Important:''' `update` and `modify` are NOT atomic across servers — they perform read-then-write. With session
  * affinity (the expected deployment model) this is safe.
  */
class RedisStateRef[F[_]: Async, A: Encoder: Decoder](
    redis: RedisCommands[F, String, String],
    hashKey: String,
    field: String,
    ttl: FiniteDuration
) extends StateRef[F, A]:

  def get: F[A] =
    redis.hGet(hashKey, field).flatMap {
      case Some(str) => Async[F].fromEither(decode[A](str))
      case None      => Async[F].raiseError(new NoSuchElementException(s"Field not found: $hashKey / $field"))
    }

  def set(a: A): F[Unit] =
    redis.hSet(hashKey, field, a.asJson.noSpaces) *>
      redis.expire(hashKey, ttl).void

  def update(f: A => A): F[Unit] =
    get.flatMap(a => set(f(a)))

  def modify[B](f: A => (A, B)): F[B] =
    get.flatMap { a =>
      val (a2, b) = f(a)
      set(a2).as(b)
    }

object RedisStateRef:

  /** Create a [[StateRef]], initializing the hash field with `initial` if it doesn't already exist.
    *
    *   - '''New session:''' writes `initial` to the field and returns a ref
    *   - '''Reconstructed session:''' uses the existing stored value
    */
  def getOrCreate[F[_]: Async, A: Encoder: Decoder](
      redis: RedisCommands[F, String, String],
      hashKey: String,
      field: String,
      initial: A,
      ttl: FiniteDuration
  ): F[StateRef[F, A]] =
    redis.hGet(hashKey, field).flatMap {
      case Some(_) =>
        Async[F].pure(new RedisStateRef(redis, hashKey, field, ttl))
      case None =>
        (redis.hSet(hashKey, field, initial.asJson.noSpaces) *>
          redis.expire(hashKey, ttl)).as(new RedisStateRef(redis, hashKey, field, ttl))
    }
