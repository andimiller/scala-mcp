package net.andimiller.mcp.redis

import cats.effect.kernel.Async
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.pubsub.PubSubCommands
import fs2.Stream
import net.andimiller.mcp.http4s.StreamingMcpHttpBuilder

import scala.concurrent.duration.*

/**
 * Convenience wiring for configuring a [[StreamingMcpHttpBuilder]] with
 * Redis-backed session management.
 *
 * Usage:
 * {{{
 * McpRedis.configure[IO, MyCtx](redis, pubSub)
 *   .apply(McpHttp.streaming[IO].name("my-server").version("1.0"))
 *   .statefulExternal[MyState]((sink, refs) => ...)
 *   ...
 * }}}
 */
object McpRedis:

  /**
   * Returns a pure function that configures a [[StreamingMcpHttpBuilder]] with Redis-backed:
   * - Session store factory (deferred construction with TTL and local caching)
   * - Notification sink factory (pub/sub per session)
   * - Session refs factory (per-session state in Redis)
   * - Cancellation registry factory (pub/sub per session — lets `notifications/cancelled`
   *   arriving on any node interrupt in-flight requests running on any other node)
   *
   * The session store is constructed at route-build time, when the builder
   * can provide a `reconstruct` callback for cache misses.
   *
   * @param redis     Redis commands for key-value operations
   * @param pubSub    Redis pub/sub commands for notifications and cancellations
   * @param ttl       TTL for session keys and state (default: 1 hour)
   */
  def configure[F[_]: Async, Ctx](
    redis: RedisCommands[F, String, String],
    pubSub: PubSubCommands[F, [x] =>> Stream[F, x], String, String],
    ttl: FiniteDuration = 1.hour
  ): StreamingMcpHttpBuilder[F, Ctx] => StreamingMcpHttpBuilder[F, Ctx] =
    builder => builder
      .withNotificationSinkFactory(id => RedisNotificationSink.create(pubSub, id))
      .withSessionRefsFactory(id => new RedisSessionRefs(redis, id, ttl))
      .withSessionStoreFactory(new RedisSessionStoreFactory(redis, ttl))
      .withCancellationRegistryFactory(id => RedisCancellationRegistry.create(pubSub, id))
