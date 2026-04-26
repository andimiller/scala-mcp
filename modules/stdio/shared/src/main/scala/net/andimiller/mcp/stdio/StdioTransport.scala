package net.andimiller.mcp.stdio

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Console
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.transport.MessageChannel
import net.andimiller.mcp.core.server.{NotificationSink, Server, ServerSession}
import net.andimiller.mcp.core.codecs.CirceCodecs.given

/**
 * Transport implementation using stdin/stdout for communication.
 *
 * Messages are sent as newline-delimited JSON over stdio.
 * This is the standard transport for MCP servers that are invoked as subprocesses.
 *
 * Supports server-initiated notifications (e.g., `notifications/progress`) by multiplexing
 * the sink's subscribe stream onto stdout alongside request responses.
 */
object StdioTransport:

  /**
   * Create a stdio message channel.
   *
   * Reads JSON-RPC messages from stdin (one per line) and writes responses to stdout.
   */
  def channel[F[_]: Async: Console]: F[MessageChannel[F]] =
    val console = Console[F]
    new MessageChannel[F]:

      def incoming: Stream[F, Message] =
        Stream.repeatEval(console.readLine)
          .filter(_.trim.nonEmpty)
          .evalMap { line =>
            decode[Message](line) match
              case Right(message) => Async[F].pure(message)
              case Left(error)   => Async[F].raiseError(new Exception(s"Failed to parse message: ${error.getMessage}"))
          }

      def send(message: Message): F[Unit] =
        console.println(message.asJson.noSpaces)

      def close: F[Unit] = Async[F].unit
    .pure[F]

  /**
   * Run an MCP server over stdio transport using a pre-built server.
   *
   * This server cannot emit progress notifications; use [[runWithSink]] if you need those.
   */
  def run[F[_]: Async: Console](server: Server[F]): F[Unit] =
    for
      channel <- StdioTransport.channel[F]
      session = new ServerSession[F](server, channel)
      _       <- session.run
    yield ()

  /**
   * Run an MCP server over stdio transport with a live notification sink so tools
   * can emit `notifications/progress`.
   *
   * The `serverFactory` receives the sink so it can pass it to any component that needs
   * to publish notifications (e.g. for tool handlers using `RequestContext.progress`).
   *
   * Outbound notifications from the sink are multiplexed onto stdout alongside request
   * responses.
   */
  def runWithSink[F[_]: Async: Console](serverFactory: NotificationSink[F] => F[Server[F]]): F[Unit] =
    NotificationSink.create[F].use { sink =>
      for
        channel <- StdioTransport.channel[F]
        server  <- serverFactory(sink)
        session  = new ServerSession[F](server, channel, sink, ServerSession.DefaultMaxConcurrent)
        outbound = sink.subscribe.evalMap(channel.send).compile.drain
        // Race: when session.run completes (stdin closed), outbound is cancelled.
        _       <- Async[F].race(session.run, outbound).void
      yield ()
    }

  /**
   * Convenience method to run a server from a server builder.
   */
  def runServer[F[_]: Async: Console](serverF: F[Server[F]]): F[Unit] =
    serverF.flatMap(run[F])
