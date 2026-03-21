package net.andimiller.mcp.stdio

import cats.effect.kernel.Async
import cats.effect.std.Console
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.transport.MessageChannel
import net.andimiller.mcp.core.server.{Server, ServerSession}
import net.andimiller.mcp.core.codecs.CirceCodecs.given

/**
 * Transport implementation using stdin/stdout for communication.
 *
 * Messages are sent as newline-delimited JSON over stdio.
 * This is the standard transport for MCP servers that are invoked as subprocesses.
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
   * Run an MCP server over stdio transport.
   *
   * This will block until stdin is closed or an error occurs.
   */
  def run[F[_]: Async: Console](server: Server[F]): F[Unit] =
    for
      channel <- StdioTransport.channel[F]
      session = new ServerSession[F](server, channel)
      _ <- session.run
    yield ()

  /**
   * Convenience method to run a server from a server builder.
   */
  def runServer[F[_]: Async: Console](serverF: F[Server[F]]): F[Unit] =
    serverF.flatMap(run[F])
