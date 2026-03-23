package net.andimiller.mcp.core.server

import cats.Applicative
import cats.effect.kernel.{Concurrent, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.jsonrpc.Message

/**
 * Abstraction for sending server-initiated notifications to clients.
 *
 * Backed by an `fs2.concurrent.Topic` so that multiple subscribers (e.g. SSE streams)
 * can each receive a copy of every published notification.
 */
trait NotificationSink[F[_]]:

  /** Send a generic notification */
  def notify(method: String, params: Json): F[Unit]

  /** Notify that a resource's content has changed */
  def resourceUpdated(uri: String): F[Unit]

  /** Notify that the resource list itself has changed */
  def resourceListChanged: F[Unit]

  /** Send a log message notification */
  def log(level: String, logger: String, data: Json): F[Unit]

  /** Subscribe to all notifications published through this sink */
  def subscribe: Stream[F, Message]

object NotificationSink:

  /** Create a live NotificationSink backed by a Topic. */
  def create[F[_]: Concurrent]: Resource[F, NotificationSink[F]] =
    Resource.eval(Topic[F, Message]).map { topic =>
      new NotificationSink[F]:
        def notify(method: String, params: Json): F[Unit] =
          topic.publish1(Message.notification(method, Some(params))).void

        def resourceUpdated(uri: String): F[Unit] =
          notify("notifications/resources/updated", Json.obj("uri" -> uri.asJson))

        def resourceListChanged: F[Unit] =
          notify("notifications/resources/list_changed", Json.obj())

        def log(level: String, logger: String, data: Json): F[Unit] =
          notify("notifications/message", Json.obj(
            "level"  -> level.asJson,
            "logger" -> logger.asJson,
            "data"   -> data
          ))

        def subscribe: Stream[F, Message] =
          topic.subscribe(256)
    }

  /** A no-op sink for transports (like stdio) that don't support server-initiated notifications. */
  def noop[F[_]: Applicative]: NotificationSink[F] =
    new NotificationSink[F]:
      def notify(method: String, params: Json): F[Unit] = Applicative[F].unit
      def resourceUpdated(uri: String): F[Unit] = Applicative[F].unit
      def resourceListChanged: F[Unit] = Applicative[F].unit
      def log(level: String, logger: String, data: Json): F[Unit] = Applicative[F].unit
      def subscribe: Stream[F, Message] = Stream.empty
