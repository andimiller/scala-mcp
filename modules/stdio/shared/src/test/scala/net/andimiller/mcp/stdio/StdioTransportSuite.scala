package net.andimiller.mcp.stdio

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.{Message, RequestId}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.transport.MessageChannel

import scala.concurrent.duration.*

class StdioTransportSuite extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 10.seconds

  private case class TestRig(
    inbound: Queue[IO, Message],
    outbound: Queue[IO, Message],
    closed: Ref[IO, Boolean]
  ):
    val channel: MessageChannel[IO] =
      MessageChannel[IO](
        incomingStream = Stream.fromQueueUnterminated(inbound),
        sendFn         = outbound.offer,
        closeFn        = closed.set(true)
      )

    def take(n: Int): IO[List[Message]] =
      List.fill(n)(()).traverse(_ => outbound.take.timeout(2.seconds))

  private def rig: IO[TestRig] =
    for
      i <- Queue.unbounded[IO, Message]
      o <- Queue.unbounded[IO, Message]
      c <- Ref.of[IO, Boolean](false)
    yield TestRig(i, o, c)

  private def echoTool: Tool.Resolved[IO] =
    new Tool.Resolved[IO]:
      val name         = "echo"
      val description  = ""
      val inputSchema  = Json.obj()
      val outputSchema = None
      def handle(arguments: Json): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text("ok")), None, false))

  private def gatedTool(gate: Deferred[IO, Unit]): Tool.Resolved[IO] =
    new Tool.Resolved[IO]:
      val name         = "slow"
      val description  = ""
      val inputSchema  = Json.obj()
      val outputSchema = None
      def handle(arguments: Json): IO[CallToolResponse] =
        gate.get.as(CallToolResponse(List(Content.Text("done")), None, false))

  private def buildSession(
    tools: List[Tool.Resolved[IO]] = Nil,
    maxConcurrent: Int = 16
  )(use: (TestRig, ClientChannel[IO]) => IO[Unit]): IO[Unit] =
    rig.flatMap { r =>
      ClientChannel.create[IO].use { cc =>
        for
          server <- DefaultServer[IO](
                      info             = Implementation("t", "0"),
                      capabilities     = ServerCapabilities(),
                      toolHandlers     = tools
                    )
          session  = new ServerSession[IO](server, r.channel, cc, ServerSessionConfig(maxConcurrent))
          fib     <- session.run.start
          _       <- use(r, cc)
          _       <- fib.cancel
        yield ()
      }
    }

  test("initialize request gets a Response on the outbound queue") {
    buildSession() { (r, _) =>
      val req = InitializeRequest("2025-11-25", ClientCapabilities.empty, Implementation("c", "1"))
      for
        _   <- r.inbound.offer(Message.request(RequestId.fromLong(1L), "initialize", Some(req.asJson)))
        out <- r.outbound.take.timeout(2.seconds)
      yield out match
        case Message.Response(_, _, Some(_), None) => ()
        case other                                  => fail(s"expected Response, got $other")
    }
  }

  test("tools/call returns a Response with the tool's content") {
    buildSession(tools = List(echoTool)) { (r, _) =>
      for
        _   <- r.inbound.offer(
                 Message.request(RequestId.fromLong(2L), "tools/call",
                   Some(CallToolRequest("echo", Json.obj()).asJson))
               )
        out <- r.outbound.take.timeout(2.seconds)
      yield out match
        case Message.Response(_, id, Some(_), None) =>
          assertEquals(id.asLong, Some(2L))
        case other => fail(s"expected Response, got $other")
    }
  }

  test("notifications/cancelled while a slow tools/call is in flight: no response is emitted") {
    val id = RequestId.fromLong(3L)
    Deferred[IO, Unit].flatMap { gate =>
      buildSession(tools = List(gatedTool(gate))) { (r, _) =>
        val cancelMsg = Message.notification(
          "notifications/cancelled",
          Some(CancelledNotificationParams(id, None).asJson)
        )
        for
          _      <- r.inbound.offer(
                      Message.request(id, "tools/call",
                        Some(CallToolRequest("slow", Json.obj()).asJson))
                    )
          _      <- IO.sleep(100.millis)
          _      <- r.inbound.offer(cancelMsg)
          peeked <- r.outbound.tryTake.delayBy(200.millis)
        yield assertEquals(peeked, None)
      }
    }
  }

  test("server-initiated notification on the ClientChannel sink appears on outbound") {
    buildSession() { (r, cc) =>
      for
        _   <- IO.sleep(100.millis)
        _   <- cc.sink.notify("notifications/test", Json.obj("k" -> "v".asJson))
        out <- r.outbound.take.timeout(2.seconds)
      yield out match
        case Message.Notification(_, method, params) =>
          assertEquals(method, "notifications/test")
          assertEquals(params, Some(Json.obj("k" -> "v".asJson)))
        case other => fail(s"expected Notification, got $other")
    }
  }

  test("maxConcurrent=2 bounds in-flight requests") {
    for
      gate    <- Deferred[IO, Unit]
      counter <- Ref.of[IO, Int](0)
      maxSeen <- Ref.of[IO, Int](0)
      tool = new Tool.Resolved[IO]:
        val name         = "concurrent"
        val description  = ""
        val inputSchema  = Json.obj()
        val outputSchema = None
        def handle(arguments: Json): IO[CallToolResponse] =
          counter.updateAndGet(_ + 1).flatMap(n => maxSeen.update(_ max n)) *>
            gate.get *>
            counter.update(_ - 1) *>
            IO.pure(CallToolResponse(List(Content.Text("d")), None, false))
      _ <- buildSession(tools = List(tool), maxConcurrent = 2) { (r, _) =>
             for
               _ <- (1 to 4).toList.traverse_(i =>
                      r.inbound.offer(
                        Message.request(RequestId.fromLong(i.toLong), "tools/call",
                          Some(CallToolRequest("concurrent", Json.obj()).asJson))
                      )
                    )
               _ <- IO.sleep(300.millis)
               n <- maxSeen.get
               _ <- gate.complete(())
               _ <- r.take(4)
             yield assertEquals(n, 2, s"expected at most 2 in flight, saw $n")
           }
    yield ()
  }
