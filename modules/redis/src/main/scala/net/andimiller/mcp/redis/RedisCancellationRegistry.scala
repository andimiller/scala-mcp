package net.andimiller.mcp.redis

import cats.effect.kernel.{Async, Deferred, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.pubsub.PubSubCommands
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId
import net.andimiller.mcp.core.server.CancellationRegistry

import scala.concurrent.duration.*

/**
 * A [[CancellationRegistry]] backed by Redis pub/sub, enabling `notifications/cancelled`
 * messages that arrive on any node to interrupt in-flight requests running on any other
 * node of a clustered MCP deployment.
 *
 * Topology: per-session Redis pub/sub channel `mcp:cancel:{sessionId}`. `cancel(id)`
 * publishes a JSON envelope; each node's own subscriber consumes the echo and completes
 * a matching local [[Deferred]] if present. `register`, `complete`, and `isActive`
 * stay local-only — every node only tracks the fibers actually running on it.
 *
 * Lifecycle: [[create]] blocks until the Redis SUBSCRIBE command has round-tripped
 * (an init ping is self-published and the Resource acquire waits on the echo), so the
 * registry is handed out only when cross-node cancels will actually be received.
 */
object RedisCancellationRegistry:

  /** Channel name for a session's cancellation broadcasts. */
  def channelName(sessionId: String): String = s"mcp:cancel:$sessionId"

  private case class CancelMessage(
    requestId: Option[RequestId],
    reason: Option[String] = None,
    init: Option[Boolean] = None
  )

  private object CancelMessage:
    given Encoder[CancelMessage] = Encoder.instance { m =>
      Json.obj(
        "requestId" -> m.requestId.asJson,
        "reason"    -> m.reason.asJson,
        "_init"     -> m.init.asJson
      ).deepDropNullValues
    }
    given Decoder[CancelMessage] = Decoder.instance { c =>
      for
        rid    <- c.get[Option[RequestId]]("requestId")
        reason <- c.get[Option[String]]("reason")
        init   <- c.get[Option[Boolean]]("_init")
      yield CancelMessage(rid, reason, init)
    }

  def create[F[_]: Async](
    pubSub: PubSubCommands[F, [x] =>> Stream[F, x], String, String],
    sessionId: String,
    readyTimeout: FiniteDuration = 2.seconds
  ): Resource[F, CancellationRegistry[F]] =
    val channel = RedisChannel(channelName(sessionId))
    for
      local <- Resource.eval(CancellationRegistry.create[F])
      ready <- Resource.eval(Deferred[F, Unit])
      _     <- pubSub.subscribe(channel)
                  .evalTap(_ => ready.complete(()).attempt.void)
                  .evalMap { raw =>
                    decode[CancelMessage](raw) match
                      case Right(msg) if msg.init.contains(true) => Async[F].unit
                      case Right(msg) => msg.requestId.fold(Async[F].unit)(local.cancel)
                      case Left(_)    => Async[F].unit // tolerate malformed messages silently
                  }
                  .compile
                  .drain
                  .background
      // Self-publish a no-op init message and wait for the subscriber to echo it back.
      // This closes the window between Resource-acquire and SUBSCRIBE-round-trip.
      _     <- Resource.eval(
                 pubSub.publish(channel, CancelMessage(None, None, Some(true)).asJson.noSpaces).void
               )
      _     <- Resource.eval(ready.get.timeout(readyTimeout))
    yield new CancellationRegistry[F]:
      def register(id: RequestId): F[Deferred[F, Unit]] = local.register(id)
      def complete(id: RequestId): F[Unit]              = local.complete(id)
      def isActive(id: RequestId): F[Boolean]           = local.isActive(id)
      def cancel(id: RequestId): F[Unit] =
        pubSub.publish(channel, CancelMessage(Some(id)).asJson.noSpaces).void
