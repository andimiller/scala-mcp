package net.andimiller.mcp.stdio

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*

import net.andimiller.mcp.core.client.ClientHandler
import net.andimiller.mcp.core.client.ClientSession
import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.client.McpProtocol
import net.andimiller.mcp.core.client.UninitializedMcpClient
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.ClientCapabilities
import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.transport.MessageChannel

import fs2.Pipe
import fs2.Stream
import fs2.io.file.Path
import fs2.io.process.ProcessBuilder
import fs2.io.process.Processes
import io.circe.parser.decode
import io.circe.syntax.*
import org.typelevel.log4cats.LoggerFactory

/** MCP client over stdio — counterpart to [[StdioTransport]] for the server side.
  *
  * Two layers:
  *   - low-level [[fromStreams]] / [[spawn]] return an [[UninitializedMcpClient]] so callers can decide when (and
  *     whether) to perform the JSON-RPC `initialize` handshake.
  *   - high-level [[builder]] wraps spawn + initialize and yields a ready [[McpClient]].
  *
  * Wire format: line-delimited JSON, mirroring [[StdioTransport]]. Each [[Message]] is encoded with `Encoder.AsObject`,
  * `.deepDropNullValues` (handled by the core codec), and `.noSpaces`, terminated with `\n`.
  */
object StdioMcpClient:

  // ── Low-level API ─────────────────────────────────────────────────

  /** Connect to a server already running and reachable through the given pipes. The caller owns the underlying process
    * — closing the resource just stops the message loop and signals EOF on the child's stdin.
    *
    * @param processIn
    *   pipe to write into the child's stdin
    * @param processOut
    *   stream of bytes from the child's stdout
    */
  def fromStreams[F[_]: Async: LoggerFactory](
      processIn: Pipe[F, Byte, Nothing],
      processOut: Stream[F, Byte]
  ): Resource[F, UninitializedMcpClient[F]] =
    fromStreams(processIn, processOut, ClientHandler.noop[F])

  /** Same as [[fromStreams]] above, but with a user-supplied [[ClientHandler]] for server-initiated requests. */
  def fromStreams[F[_]: Async: LoggerFactory](
      processIn: Pipe[F, Byte, Nothing],
      processOut: Stream[F, Byte],
      handler: ClientHandler[F]
  ): Resource[F, UninitializedMcpClient[F]] =
    for
      outbound <- Resource.eval(Queue.unbounded[F, Option[String]])
      _        <- Stream
             .fromQueueNoneTerminated(outbound)
             .through(fs2.text.utf8.encode)
             .through(processIn)
             .compile
             .drain
             .background
      channel = stdioChannel[F](processOut, outbound)
      client <- ClientSession.resource[F](channel, handler)
    yield client

  /** Spawn a server subprocess and connect to it. Cross-platform via [[fs2.io.process.Processes]] — works on JVM,
    * Native, and JS (Node only).
    *
    * The child's stderr is consumed and discarded. If you need to surface it, drop down to [[fromStreams]] and wire it
    * yourself.
    */
  def spawn[F[_]: Async: LoggerFactory](
      command: String,
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty,
      workingDirectory: Option[Path] = None
  ): Resource[F, UninitializedMcpClient[F]] =
    spawn(command, args, env, workingDirectory, ClientHandler.noop[F])

  /** Same as [[spawn]] above, but with a user-supplied [[ClientHandler]]. */
  def spawn[F[_]: Async: LoggerFactory](
      command: String,
      args: List[String],
      env: Map[String, String],
      workingDirectory: Option[Path],
      handler: ClientHandler[F]
  ): Resource[F, UninitializedMcpClient[F]] =
    val processes = Processes.forAsync[F]
    val pb        = workingDirectory
      .foldLeft(ProcessBuilder(command, args).withExtraEnv(env))((b, wd) => b.withWorkingDirectory(wd))
    processes.spawn(pb).flatMap { proc =>
      // drain stderr concurrently so the child's pipe buffer doesn't fill up
      Stream.emit(()).concurrently(proc.stderr.drain).compile.drain.background.flatMap { _ =>
        fromStreams(proc.stdin, proc.stdout, handler)
      }
    }

  // ── High-level API: builder ───────────────────────────────────────

  /** Entry point for the fluent builder. Configure the subprocess and the initialize parameters, then call
    * [[StdioClientBuilder.connect]] to get a fully-initialised [[McpClient]].
    */
  def builder[F[_]: Async: LoggerFactory]: StdioClientBuilder[F] = StdioClientBuilder.empty[F]

  // ── Internals ─────────────────────────────────────────────────────

  private def stdioChannel[F[_]: Async](
      processOut: Stream[F, Byte],
      outbound: Queue[F, Option[String]]
  ): MessageChannel[F] = new MessageChannel[F]:

    def incoming: Stream[F, Message] =
      processOut
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .filter(_.trim.nonEmpty)
        .evalMap { line =>
          decode[Message](line) match
            case Right(m) => Async[F].pure(m)
            case Left(e)  =>
              Async[F].raiseError(new RuntimeException(s"Failed to parse stdio message: ${e.getMessage}\n  $line"))
        }

    def send(message: Message): F[Unit] =
      outbound.offer(Some(message.asJson.noSpaces + "\n"))

    def close: F[Unit] = outbound.offer(None)

/** Fluent builder for [[StdioMcpClient]] that wraps spawn + initialize. */
final class StdioClientBuilder[F[_]: Async: LoggerFactory] private (
    private val command: Option[String],
    private val args: List[String],
    private val env: Map[String, String],
    private val workingDirectory: Option[Path],
    private val info: Option[Implementation],
    private val capabilities: ClientCapabilities,
    private val protocolVersion: String,
    private val handler: ClientHandler[F]
):

  private def copy(
      command: Option[String] = command,
      args: List[String] = args,
      env: Map[String, String] = env,
      workingDirectory: Option[Path] = workingDirectory,
      info: Option[Implementation] = info,
      capabilities: ClientCapabilities = capabilities,
      protocolVersion: String = protocolVersion,
      handler: ClientHandler[F] = handler
  ): StdioClientBuilder[F] =
    new StdioClientBuilder[F](command, args, env, workingDirectory, info, capabilities, protocolVersion, handler)

  def withCommand(command: String, args: List[String] = Nil): StdioClientBuilder[F] =
    copy(command = Some(command), args = args)

  def withArgs(args: List[String]): StdioClientBuilder[F] = copy(args = args)

  def withEnv(env: Map[String, String]): StdioClientBuilder[F] = copy(env = env)

  def withWorkingDirectory(path: Path): StdioClientBuilder[F] = copy(workingDirectory = Some(path))

  def withInfo(info: Implementation): StdioClientBuilder[F] = copy(info = Some(info))

  def withCapabilities(capabilities: ClientCapabilities): StdioClientBuilder[F] = copy(capabilities = capabilities)

  def withProtocolVersion(version: String): StdioClientBuilder[F] = copy(protocolVersion = version)

  def withHandler(handler: ClientHandler[F]): StdioClientBuilder[F] = copy(handler = handler)

  /** Spawn the child server, perform the JSON-RPC `initialize` handshake, and yield a ready [[McpClient]]. */
  def connect: Resource[F, McpClient[F]] =
    for
      cmd <- Resource.eval(
               command.fold[F[String]](
                 Async[F].raiseError(new IllegalStateException("StdioClientBuilder: command is required"))
               )(Async[F].pure)
             )
      info0 <- Resource.eval(
                 info.fold[F[Implementation]](
                   Async[F].raiseError(new IllegalStateException("StdioClientBuilder: info is required"))
                 )(Async[F].pure)
               )
      uninit <- StdioMcpClient.spawn[F](cmd, args, env, workingDirectory, handler)
      client <- Resource.eval(uninit.initialize(info0, capabilities, protocolVersion))
    yield client

object StdioClientBuilder:

  def empty[F[_]: Async: LoggerFactory]: StdioClientBuilder[F] = new StdioClientBuilder[F](
    command = None,
    args = Nil,
    env = Map.empty,
    workingDirectory = None,
    info = None,
    capabilities = ClientCapabilities.empty,
    protocolVersion = McpProtocol.DefaultVersion,
    handler = ClientHandler.noop[F]
  )
