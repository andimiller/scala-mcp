package net.andimiller.mcp.stdio

import cats.effect.kernel.Async
import cats.effect.std.Console
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.transport.MessageChannel
import net.andimiller.mcp.core.server.{ClientChannel, Server, ServerSession, ServerSessionConfig, SessionContext}
import net.andimiller.mcp.core.state.SessionRefs
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import cats.effect.LiftIO

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
  def channel[F[_]: Async: LiftIO: Console]: F[MessageChannel[F]] =
    val console = Console[F]
    new MessageChannel[F]:

      def incoming: Stream[F, Message] =
        fs2.io.stdin[F](1024)
          .through(fs2.text.utf8.decode)
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
   * Run an MCP server over stdio transport using a pre-built server and client channel.
   *
   * This will block until stdin is closed or an error occurs.
   */
  def run[F[_]: Async: LiftIO: Console](
    server: Server[F],
    clientChannel: ClientChannel[F],
    config: ServerSessionConfig = ServerSessionConfig.default
  ): F[Unit] =
    for
      channel <- StdioTransport.channel[F]
      session  = new ServerSession[F](server, channel, clientChannel, config)
      _       <- session.run
    yield ()

  /**
   * Run an MCP server over stdio transport. Creates an internal [[ClientChannel]] for the
   * transport's outbound traffic. Use the `SessionContext`-taking overload if the server
   * itself needs access to the channel (e.g. for notifications or elicitation requests).
   */
  def run[F[_]: Async: LiftIO: Console](server: Server[F], config: ServerSessionConfig): F[Unit] =
    ClientChannel.create[F].use(cc => run(server, cc, config))

  def run[F[_]: Async: LiftIO: Console](server: Server[F]): F[Unit] =
    run(server, ServerSessionConfig.default)

  /**
   * Run a server built from a [[SessionContext]] factory — the unified shape that grants
   * access to the per-session [[ClientChannel]] (notifications + server-initiated requests
   * such as `elicitation/create`) and an in-memory [[SessionRefs]].
   *
   * Stdio is single-session, so the context's id is the literal `"stdio"`.
   */
  def run[F[_]: Async: LiftIO: Console](
    factory: SessionContext[F] => F[Server[F]],
    config: ServerSessionConfig
  ): F[Unit] =
    ClientChannel.create[F].use { cc =>
      val ctx = SessionContext[F]("stdio", cc, SessionRefs.inMemory[F])
      factory(ctx).flatMap(run(_, cc, config))
    }

  def run[F[_]: Async: LiftIO: Console](factory: SessionContext[F] => F[Server[F]]): F[Unit] =
    run(factory, ServerSessionConfig.default)

  /**
   * Convenience method to run a server from an `F`-wrapped server.
   */
  def runServer[F[_]: Async: LiftIO: Console](serverF: F[Server[F]]): F[Unit] =
    serverF.flatMap(run[F])
