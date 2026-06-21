package net.andimiller.mcp.stdio

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.syntax.all.*

import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.server.ClientChannel
import net.andimiller.mcp.core.server.DefaultServer
import net.andimiller.mcp.core.server.ServerSession
import net.andimiller.mcp.core.server.ServerSessionConfig
import net.andimiller.mcp.core.server.Tool
import net.andimiller.mcp.core.server.ToolCallContext
import net.andimiller.mcp.core.transport.MessageChannel

import fs2.Chunk
import fs2.Pipe
import fs2.Stream
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.testing.TestingLoggerFactory

/** End-to-end test for [[StdioMcpClient.fromStreams]] over paired in-memory byte pipes. Exercises the line-delimited
  * JSON-RPC wire format without spawning a subprocess.
  */
class StdioMcpClientSuite extends CatsEffectSuite:

  private given LoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()

  override def munitIOTimeout: FiniteDuration = 10.seconds

  /** Two byte-pipe pairs: writes into `a.sink` show up on `b.source`, and vice-versa. Each pair plays the role of one
    * side's stdin (sink) + stdout (source).
    */
  private case class BytePipe(sink: Pipe[IO, Byte, Nothing], source: Stream[IO, Byte])

  private def pairedBytePipes: IO[(BytePipe, BytePipe)] =
    for
      aQ <- Queue.unbounded[IO, Option[Chunk[Byte]]]
      bQ <- Queue.unbounded[IO, Option[Chunk[Byte]]]
    yield
      val a = BytePipe(
        sink = _.chunks.evalMap(c => bQ.offer(Some(c))).onFinalize(bQ.offer(None)).drain,
        source = Stream.fromQueueNoneTerminatedChunk(aQ)
      )
      val b = BytePipe(
        sink = _.chunks.evalMap(c => aQ.offer(Some(c))).onFinalize(aQ.offer(None)).drain,
        source = Stream.fromQueueNoneTerminatedChunk(bQ)
      )
      (a, b)

  /** Build a [[MessageChannel]] for the *server* side over arbitrary byte pipes — the same line-delimited JSON protocol
    * as the production [[StdioTransport]], parameterised on the pipes so we can wire it up in tests.
    */
  private def serverChannel(in: Stream[IO, Byte], out: Pipe[IO, Byte, Nothing]): IO[(MessageChannel[IO], IO[Unit])] =
    Queue.unbounded[IO, Option[String]].map { outbound =>
      val drain = Stream
        .fromQueueNoneTerminated(outbound)
        .through(fs2.text.utf8.encode)
        .through(out)
        .compile
        .drain
      val channel = new MessageChannel[IO]:
        def incoming: Stream[IO, Message] =
          in.through(fs2.text.utf8.decode)
            .through(fs2.text.lines)
            .filter(_.trim.nonEmpty)
            .evalMap { line =>
              decode[Message](line) match
                case Right(m) => IO.pure(m)
                case Left(e)  => IO.raiseError(new RuntimeException(s"server-side parse failed: ${e.getMessage}"))
            }
        def send(message: Message): IO[Unit] = outbound.offer(Some(message.asJson.noSpaces + "\n"))
        def close: IO[Unit]                  = outbound.offer(None)
      (channel, drain)
    }

  private def echoTool: Tool[IO, Unit] = new Tool[IO, Unit]:
    val name                                                          = "echo"
    val description                                                   = "echo input"
    val inputSchema                                                   = Json.obj("type" -> "object".asJson)
    val outputSchema                                                  = None
    def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] =
      IO.pure(CallToolResponse(List(Content.Text(s"echo:${call.request.arguments.noSpaces}")), None, isError = false))

  /** Wire up:
    *   - server side runs a real `ServerSession` reading from `serverIn` (= bytes the client wrote) and writing to
    *     `serverOut` (= bytes the client will read);
    *   - client side runs `StdioMcpClient.fromStreams` reading from `clientIn` and writing to `clientOut`.
    */
  private def harness[A](use: McpClient[IO] => IO[A]): IO[A] =
    pairedBytePipes.flatMap { case (clientPipe, serverPipe) =>
      val resource =
        for
          // Server side: real DefaultServer with one tool, real ServerSession over byte channel
          server <- Resource.eval(
                      DefaultServer[IO](
                        info = Implementation("paired-server", "1.0"),
                        capabilities = ServerCapabilities.withTools(),
                        toolHandlers = List(echoTool)
                      )
                    )
          chDrain         <- Resource.eval(serverChannel(serverPipe.source, serverPipe.sink))
          (chan, drainOut) = chDrain
          cc              <- ClientChannel.create[IO]
          session          = new ServerSession[IO]("test", server, chan, cc, ServerSessionConfig.default)
          _               <- session.run.background
          _               <- drainOut.background
          // Client side: StdioMcpClient.fromStreams + initialize
          uninit <- StdioMcpClient.fromStreams[IO](clientPipe.sink, clientPipe.source)
          client <- Resource.eval(uninit.initialize(Implementation("paired-client", "0.1.0")))
        yield client
      resource.use(use)
    }

  test("initialize, listTools, callTool, ping over paired byte pipes") {
    harness { client =>
      for
        tools <- client.listTools()
        res   <- client.callTool("echo", Json.obj("k" -> "v".asJson))
        _     <- client.ping()
      yield
        assertEquals(client.serverInfo.name, "paired-server")
        assertEquals(client.protocolVersion, "2025-11-25")
        assertEquals(tools.tools.map(_.name), List("echo"))
        assertEquals(res.isError, false)
        assertEquals(res.content.headOption, Some(Content.Text("""echo:{"k":"v"}""")))
    }
  }
