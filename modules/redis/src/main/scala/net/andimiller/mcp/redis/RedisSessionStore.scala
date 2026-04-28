package net.andimiller.mcp.redis

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*
import dev.profunktor.redis4cats.RedisCommands
import io.circe.Decoder
import io.circe.Encoder
import io.circe.parser.decode
import io.circe.syntax.*
import net.andimiller.mcp.http4s.AuthenticatedSessionStore
import net.andimiller.mcp.http4s.McpSession
import net.andimiller.mcp.http4s.SessionStore
import net.andimiller.mcp.http4s.SessionStoreFactory

import scala.concurrent.duration.FiniteDuration

/** A [[SessionStore]] that uses a Redis hash for session registry (with TTL) and a local in-memory cache for live
  * `McpSession` objects (which contain non-serializable handles like request handlers and notification sinks).
  *
  * Each session's hash at `mcp:session:{id}` contains an `_active` field as well as any state fields written by
  * [[RedisSessionRefs]].
  *
  * When a session is not in the local cache but the hash exists in Redis, the `reconstruct` callback rebuilds the
  * session (creating new Redis-backed sinks and state refs that automatically pick up the persisted state fields).
  */
class RedisSessionStore[F[_]: Async](
    redis: RedisCommands[F, String, String],
    localCache: Ref[F, Map[String, McpSession[F]]],
    reconstruct: String => F[McpSession[F]],
    ttl: FiniteDuration
) extends SessionStore[F]:

  private def key(id: String): String = s"mcp:session:$id"

  def put(session: McpSession[F]): F[McpSession[F]] =
    redis.hSet(key(session.id), "_active", "true") *>
      redis.expire(key(session.id), ttl) *>
      localCache.update(_ + (session.id -> session)).as(session)

  def get(id: String): F[Option[McpSession[F]]] =
    redis.hGet(key(id), "_active").flatMap {
      case None =>
        localCache.modify(m => (m - id, None))
      case Some(_) =>
        redis.expire(key(id), ttl) *>
          localCache.get.map(_.get(id)).flatMap {
            case Some(s) => Async[F].pure(Some(s))
            case None    =>
              reconstruct(id).flatMap(s => localCache.update(_ + (id -> s)).as(Some(s)))
          }
    }

  def remove(id: String): F[Unit] =
    redis.del(key(id)) *> localCache.update(_ - id).void

object RedisSessionStore:

  def make[F[_]: Async](
      redis: RedisCommands[F, String, String],
      reconstruct: String => F[McpSession[F]],
      ttl: FiniteDuration
  ): F[SessionStore[F]] =
    Ref.of[F, Map[String, McpSession[F]]](Map.empty).map { cache =>
      new RedisSessionStore(redis, cache, reconstruct, ttl)
    }

class RedisSessionStoreFactory[F[_]: Async](
    redis: RedisCommands[F, String, String],
    ttl: FiniteDuration
) extends SessionStoreFactory[F]:

  def create(reconstruct: String => F[McpSession[F]]): F[SessionStore[F]] =
    RedisSessionStore.make(redis, reconstruct, ttl)

/** A [[AuthenticatedSessionStore]] that extends [[RedisSessionStore]] with Redis-backed user identity storage (stored
  * as a field in the session hash).
  */
class RedisAuthenticatedSessionStore[F[_]: Async, U: Encoder: Decoder](
    redis: RedisCommands[F, String, String],
    localCache: Ref[F, Map[String, McpSession[F]]],
    reconstruct: String => F[McpSession[F]],
    ttl: FiniteDuration
) extends RedisSessionStore[F](redis, localCache, reconstruct, ttl)
    with AuthenticatedSessionStore[F, U]:

  private def key(id: String): String = s"mcp:session:$id"

  private val userField = "_user"

  def putUser(sessionId: String, user: U): F[Unit] =
    redis.hSet(key(sessionId), userField, user.asJson.noSpaces) *>
      redis.expire(key(sessionId), ttl).void

  def getUser(sessionId: String): F[Option[U]] =
    redis.hGet(key(sessionId), userField).flatMap {
      case Some(str) => Async[F].fromEither(decode[U](str)).map(Some(_))
      case None      => Async[F].pure(None)
    }

  def removeUser(sessionId: String): F[Unit] =
    redis.hDel(key(sessionId), userField).void

object RedisAuthenticatedSessionStore:

  def make[F[_]: Async, U: Encoder: Decoder](
      redis: RedisCommands[F, String, String],
      reconstruct: String => F[McpSession[F]],
      ttl: FiniteDuration
  ): F[AuthenticatedSessionStore[F, U]] =
    Ref.of[F, Map[String, McpSession[F]]](Map.empty).map { cache =>
      new RedisAuthenticatedSessionStore(redis, cache, reconstruct, ttl)
    }
