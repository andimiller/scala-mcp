package net.andimiller.mcp.core.server

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import net.andimiller.mcp.core.protocol.jsonrpc.Message

/**
 * Bundle of every server→client communication primitive.
 *
 * Both [[NotificationSink]] (one-way notifications) and [[ServerRequester]] (request/response
 * with correlation) publish to a single shared `fs2.concurrent.Topic[F, Message]`. The
 * [[subscribe]] stream is the canonical outbound feed consumed by the SSE channel (HTTP) or
 * the stdout writer (stdio).
 *
 * Server factories receive a `ClientChannel[F]` rather than a `NotificationSink[F]`, so adding
 * future server-initiated features (sampling, roots/list) does not break factory signatures.
 */
trait ClientChannel[F[_]]:
  def sink: NotificationSink[F]
  def requester: ServerRequester[F]
  def subscribe: Stream[F, Message]

object ClientChannel:

  /** Create a live ClientChannel backed by a single shared Topic. */
  def create[F[_]: Async]: Resource[F, ClientChannel[F]] =
    for
      topic <- Resource.eval(Topic[F, Message])
      req   <- Resource.eval(ServerRequester.create[F](msg => topic.publish1(msg).void))
    yield new ClientChannel[F]:
      val sink: NotificationSink[F]     = NotificationSink.fromTopic(topic)
      val requester: ServerRequester[F] = req
      def subscribe: Stream[F, Message] = topic.subscribe(256)

  /**
   * Build a ClientChannel that wraps an existing [[NotificationSink]] (e.g. a user-provided
   * Redis-backed sink). The requester gets its own internal Topic; the [[subscribe]] stream
   * merges both so a single outbound feed (SSE / stdout) carries both notifications and
   * server-initiated requests.
   */
  def fromSink[F[_]: Async](existing: NotificationSink[F]): Resource[F, ClientChannel[F]] =
    for
      topic <- Resource.eval(Topic[F, Message])
      req   <- Resource.eval(ServerRequester.create[F](msg => topic.publish1(msg).void))
    yield new ClientChannel[F]:
      val sink: NotificationSink[F]     = existing
      val requester: ServerRequester[F] = req
      def subscribe: Stream[F, Message] = existing.subscribe.merge(topic.subscribe(256))

  /** A no-op channel for transports/tests that don't support server-initiated traffic. */
  def noop[F[_]: Async]: F[ClientChannel[F]] =
    ServerRequester.noop[F].map { req =>
      new ClientChannel[F]:
        val sink: NotificationSink[F]     = NotificationSink.noop[F]
        val requester: ServerRequester[F] = req
        def subscribe: Stream[F, Message] = Stream.empty
    }
