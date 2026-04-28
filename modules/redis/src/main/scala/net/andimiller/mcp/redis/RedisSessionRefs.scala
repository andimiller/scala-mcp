package net.andimiller.mcp.redis

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Async

import net.andimiller.mcp.core.state.SessionRefs
import net.andimiller.mcp.core.state.StateRef

import dev.profunktor.redis4cats.RedisCommands
import io.circe.Decoder
import io.circe.Encoder

/** A [[SessionRefs]] backed by Redis hash fields, keyed by session ID.
  *
  * All state for a session is stored as fields in a single hash at `mcp:session:{sessionId}`. Each `ref` call creates
  * (or reuses) a field within that hash. If the field already exists (session reconstruction), the stored value is
  * used; otherwise the provided `initial` value is written.
  */
class RedisSessionRefs[F[_]: Async](
    redis: RedisCommands[F, String, String],
    sessionId: String,
    ttl: FiniteDuration
) extends SessionRefs[F]:

  private val hashKey = s"mcp:session:$sessionId"

  def ref[A: Encoder: Decoder](name: String, initial: A): F[StateRef[F, A]] =
    RedisStateRef.getOrCreate(redis, hashKey, s"state:$name", initial, ttl)
