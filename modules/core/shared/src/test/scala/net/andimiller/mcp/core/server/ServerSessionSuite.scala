package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.{Message, RequestId}
import net.andimiller.mcp.core.transport.MessageChannel

import scala.concurrent.duration.*

class ServerSessionSuite extends CatsEffectSuite:

  /** A bidirectional in-memory channel for wiring tests. */
  private def testChannel: IO[(MessageChannel[IO], IO[List[Message]], Message => IO[Unit])] =
    for
      inbound  <- Queue.unbounded[IO, Option[Message]]
      outbound <- Queue.unbounded[IO, Message]
      channel = new MessageChannel[IO]:
        def incoming: Stream[IO, Message] = Stream.fromQueueNoneTerminated(inbound)
        def send(message: Message): IO[Unit] = outbound.offer(message)
        def close: IO[Unit] = inbound.offer(None)
      drainOutbound = Stream.fromQueueUnterminated(outbound).compile.toList // unused but available
      sendIn        = (m: Message) => inbound.offer(Some(m))
      readOut       = outbound.size.flatMap(n =>
                        List.fill(n.toInt)(()).traverse(_ => outbound.take)
                      )
    yield (channel, readOut, sendIn)

  private def makeServer(toolHandler: (CallToolRequest, RequestContext[IO]) => IO[CallToolResponse]): Server[IO] =
    new Server[IO]:
      def info: Implementation             = Implementation("test", "0.0.0")
      def capabilities: ServerCapabilities = ServerCapabilities()
      def listTools(r: ListToolsRequest): IO[ListToolsResponse] =
        IO.pure(ListToolsResponse(Nil, None))
      def callTool(r: CallToolRequest, rc: RequestContext[IO]): IO[CallToolResponse] =
        toolHandler(r, rc)
      def listResources(r: ListResourcesRequest): IO[ListResourcesResponse] =
        IO.pure(ListResourcesResponse(Nil, None))
      def readResource(r: ReadResourceRequest): IO[ReadResourceResponse] =
        IO.raiseError(new Exception("no resources"))
      def listResourceTemplates(r: ListResourceTemplatesRequest): IO[ListResourceTemplatesResponse] =
        IO.pure(ListResourceTemplatesResponse(Nil, None))
      def subscribe(r: SubscribeRequest): IO[Unit]     = IO.unit
      def unsubscribe(r: UnsubscribeRequest): IO[Unit] = IO.unit
      def listPrompts(r: ListPromptsRequest): IO[ListPromptsResponse] =
        IO.pure(ListPromptsResponse(Nil, None))
      def getPrompt(r: GetPromptRequest): IO[GetPromptResponse] =
        IO.raiseError(new Exception("no prompts"))
      def ping(): IO[Unit] = IO.unit

  test("two concurrent tool calls complete out-of-order (proves fiber parallelism)") {
    for
      inbound  <- Queue.unbounded[IO, Option[Message]]
      outbound <- Queue.unbounded[IO, Message]
      channel = new MessageChannel[IO]:
        def incoming: Stream[IO, Message] = Stream.fromQueueNoneTerminated(inbound)
        def send(message: Message): IO[Unit] = outbound.offer(message)
        def close: IO[Unit] = inbound.offer(None)
      // Tool: sleep for the duration encoded in the "delay" argument, then return.
      server = makeServer { (req, _) =>
        val delayMs = req.arguments.hcursor.get[Long]("delay").getOrElse(0L)
        IO.sleep(delayMs.millis).as(CallToolResponse(Nil, None, false))
      }
      session = ServerSession(server, channel, NotificationSink.noop[IO], 8)
      sessionFiber <- session.run.start
      // Issue request A (long), then request B (short). If processing is concurrent,
      // B's response comes back first.
      _ <- inbound.offer(Some(callTool(1, "slow", 500L)))
      _ <- inbound.offer(Some(callTool(2, "fast", 50L)))
      first  <- outbound.take.timeout(5.seconds)
      second <- outbound.take.timeout(5.seconds)
      _      <- inbound.offer(None)
      _      <- sessionFiber.join
    yield
      val firstId = first match
        case Message.Response(_, id, _, _) => id.asLong.orElse(id.asString.map(_.toLong))
        case _                              => None
      assertEquals(firstId, Some(2L), "fast request should complete first under concurrent handling")
  }

  test("cancellation of request A does not affect concurrent request B") {
    for
      inbound  <- Queue.unbounded[IO, Option[Message]]
      outbound <- Queue.unbounded[IO, Message]
      channel = new MessageChannel[IO]:
        def incoming: Stream[IO, Message] = Stream.fromQueueNoneTerminated(inbound)
        def send(message: Message): IO[Unit] = outbound.offer(message)
        def close: IO[Unit] = inbound.offer(None)
      started <- IO.deferred[Unit]
      server = makeServer { (req, _) =>
        req.arguments.hcursor.get[String]("mode").toOption match
          case Some("hang") => started.complete(()).attempt *> IO.never[CallToolResponse]
          case _            => IO.sleep(50.millis).as(CallToolResponse(Nil, None, false))
      }
      session = ServerSession(server, channel, NotificationSink.noop[IO], 8)
      sessionFiber <- session.run.start
      // A: will hang. B: will complete normally.
      _ <- inbound.offer(Some(
        Message.Request(
          "2.0", RequestId.fromLong(1L), "tools/call",
          Some(Json.obj("name" -> "hang".asJson, "arguments" -> Json.obj("mode" -> "hang".asJson)))
        )))
      _ <- started.get // wait for A to actually begin
      _ <- inbound.offer(Some(
        Message.Request(
          "2.0", RequestId.fromLong(2L), "tools/call",
          Some(Json.obj("name" -> "quick".asJson, "arguments" -> Json.obj()))
        )))
      // Cancel A
      _ <- inbound.offer(Some(
        Message.Notification(
          "2.0", "notifications/cancelled",
          Some(Json.obj("requestId" -> 1.asJson))
        )))
      r1 <- outbound.take.timeout(5.seconds)
      r2 <- outbound.take.timeout(5.seconds)
      _  <- inbound.offer(None)
      _  <- sessionFiber.join
    yield
      val responseIds = List(r1, r2).collect {
        case Message.Response(_, id, _, _) => id.asLong.getOrElse(id.asString.map(_.toLong).getOrElse(-1L))
      }
      assert(responseIds.contains(1L), s"expected cancellation response for id 1; got: $responseIds")
      assert(responseIds.contains(2L), s"expected normal response for id 2; got: $responseIds")
  }

  private def callTool(id: Long, name: String, delayMs: Long): Message =
    Message.Request(
      "2.0",
      RequestId.fromLong(id),
      "tools/call",
      Some(Json.obj(
        "name"      -> name.asJson,
        "arguments" -> Json.obj("delay" -> delayMs.asJson)
      ))
    )
