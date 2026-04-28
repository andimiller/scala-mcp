package net.andimiller.mcp.redis

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all.*
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.pubsub.PubSubCommands
import fs2.Stream
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.server.NotificationSink

/** A [[NotificationSink]] backed by Redis pub/sub, enabling notifications to route across multiple server instances.
  *
  * Publishes to and subscribes from channel `mcp:notifications:{sessionId}`.
  */
object RedisNotificationSink:

  def create[F[_]: Async](
      pubSub: PubSubCommands[F, [x] =>> Stream[F, x], String, String],
      sessionId: String
  ): Resource[F, NotificationSink[F]] =
    val channel = RedisChannel(s"mcp:notifications:$sessionId")
    Resource.pure(
      new NotificationSink[F]:
        def notify(method: String, params: Json): F[Unit] =
          val msg = Message.notification(method, Some(params))
          pubSub.publish(channel, msg.asJson.noSpaces).void

        def resourceUpdated(uri: String): F[Unit] =
          notify("notifications/resources/updated", Json.obj("uri" -> uri.asJson))

        def resourceListChanged: F[Unit] =
          notify("notifications/resources/list_changed", Json.obj())

        def log(level: String, logger: String, data: Json): F[Unit] =
          notify(
            "notifications/message",
            Json.obj(
              "level"  -> level.asJson,
              "logger" -> logger.asJson,
              "data"   -> data
            )
          )

        def subscribe: Stream[F, Message] =
          pubSub.subscribe(channel).evalMap { str =>
            Async[F].fromEither(decode[Message](str))
          }
    )
