package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.{JsonRpcError, Message, RequestId}

import scala.concurrent.duration.*

class RequestHandlerSuite extends CatsEffectSuite:

  /** A minimal server that only exposes a single `tools/call` handler parameterised by F. */
  private def makeServer(toolHandler: (CallToolRequest, RequestContext[IO]) => IO[CallToolResponse]): Server[IO] =
    new Server[IO]:
      def info: Implementation            = Implementation("test", "0.0.0")
      def capabilities: ServerCapabilities = ServerCapabilities()
      def listTools(r: ListToolsRequest): IO[ListToolsResponse] =
        IO.pure(ListToolsResponse(Nil, None))
      def callTool(r: CallToolRequest, rc: RequestContext[IO]): IO[CallToolResponse] = toolHandler(r, rc)
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

  test("progressToken from _meta is threaded to the tool handler") {
    for
      sink     <- NotificationSink.create[IO].allocated.map(_._1)
      registry <- CancellationRegistry.create[IO]
      capturedToken <- IO.ref(Option.empty[Option[ProgressToken]])
      server = makeServer { (_, rc) =>
        capturedToken.set(Some(rc.progressToken)).as(CallToolResponse(Nil, None, false))
      }
      handler = new RequestHandler[IO](server, sink, registry)
      params = Json.obj(
        "name"      -> "anything".asJson,
        "arguments" -> Json.obj(),
        "_meta"     -> Json.obj("progressToken" -> "abc".asJson)
      )
      _      <- handler.handle(Message.Request("2.0", RequestId.fromLong(1), "tools/call", Some(params)))
      result <- capturedToken.get
    yield
      assertEquals(
        result.flatten.flatMap(_.asString),
        Some("abc")
      )
  }

  test("numeric progressToken is decoded as a Long") {
    for
      sink     <- NotificationSink.create[IO].allocated.map(_._1)
      registry <- CancellationRegistry.create[IO]
      capturedToken <- IO.ref(Option.empty[Option[ProgressToken]])
      server = makeServer { (_, rc) =>
        capturedToken.set(Some(rc.progressToken)).as(CallToolResponse(Nil, None, false))
      }
      handler = new RequestHandler[IO](server, sink, registry)
      params = Json.obj(
        "name"      -> "anything".asJson,
        "arguments" -> Json.obj(),
        "_meta"     -> Json.obj("progressToken" -> 99.asJson)
      )
      _      <- handler.handle(Message.Request("2.0", RequestId.fromLong(1), "tools/call", Some(params)))
      result <- capturedToken.get
    yield assertEquals(result.flatten.flatMap(_.asLong), Some(99L))
  }

  test("missing progressToken yields None") {
    for
      sink     <- NotificationSink.create[IO].allocated.map(_._1)
      registry <- CancellationRegistry.create[IO]
      capturedToken <- IO.ref(Option.empty[Option[ProgressToken]])
      server = makeServer { (_, rc) =>
        capturedToken.set(Some(rc.progressToken)).as(CallToolResponse(Nil, None, false))
      }
      handler = new RequestHandler[IO](server, sink, registry)
      params = Json.obj("name" -> "anything".asJson, "arguments" -> Json.obj())
      _      <- handler.handle(Message.Request("2.0", RequestId.fromLong(1), "tools/call", Some(params)))
      result <- capturedToken.get
    yield assertEquals(result.flatten, Option.empty[ProgressToken])
  }

  test("notifications/cancelled interrupts an in-flight request") {
    for
      sink     <- NotificationSink.create[IO].allocated.map(_._1)
      registry <- CancellationRegistry.create[IO]
      started  <- IO.deferred[Unit]
      server = makeServer { (_, _) =>
        // A long-running tool that never completes on its own — simulates a real
        // tool doing network I/O that only ends when cancelled.
        started.complete(()) *> IO.never[CallToolResponse]
      }
      handler   = new RequestHandler[IO](server, sink, registry)
      reqId     = RequestId.fromLong(7L)
      requestMsg = Message.Request(
        "2.0",
        reqId,
        "tools/call",
        Some(Json.obj("name" -> "x".asJson, "arguments" -> Json.obj()))
      )
      cancelMsg = Message.Notification(
        "2.0",
        "notifications/cancelled",
        Some(Json.obj("requestId" -> 7.asJson, "reason" -> "user aborted".asJson))
      )
      requestFiber <- handler.handle(requestMsg).start
      _            <- started.get
      _            <- handler.handle(cancelMsg)
      response     <- requestFiber.joinWithNever.timeout(2.seconds)
    yield
      response match
        case Some(Message.Response(_, id, _, Some(err))) =>
          assertEquals(id, reqId)
          assertEquals(err.code, -32800)
        case other =>
          fail(s"expected -32800 error response, got: $other")
  }

  test("a cooperatively-cancellable tool can race ctx.cancelled against its work") {
    for
      sink     <- NotificationSink.create[IO].allocated.map(_._1)
      registry <- CancellationRegistry.create[IO]
      started  <- IO.deferred[Unit]
      server = makeServer { (_, rc) =>
        // Explicit race: if cancelled fires first, emit an "isError" response instead of
        // being hard-cancelled. This validates that tools can observe cancellation.
        started.complete(()) *> IO.race(rc.cancelled, IO.never[CallToolResponse]).map {
          case Left(_)         => CallToolResponse(Nil, None, true)
          case Right(response) => response
        }
      }
      handler   = new RequestHandler[IO](server, sink, registry)
      reqId     = RequestId.fromLong(8L)
      requestMsg = Message.Request(
        "2.0", reqId, "tools/call",
        Some(Json.obj("name" -> "x".asJson, "arguments" -> Json.obj()))
      )
      cancelMsg = Message.Notification(
        "2.0", "notifications/cancelled",
        Some(Json.obj("requestId" -> 8.asJson))
      )
      requestFiber <- handler.handle(requestMsg).start
      _            <- started.get
      _            <- handler.handle(cancelMsg)
      response     <- requestFiber.joinWithNever.timeout(2.seconds)
    yield
      // Either the outer race won (-32800) or the inner one did (isError=true).
      // Both represent valid cancellation outcomes; we just assert we didn't hang.
      assert(response.isDefined)
  }

  test("notifications/cancelled for unknown id is a no-op") {
    for
      sink     <- NotificationSink.create[IO].allocated.map(_._1)
      registry <- CancellationRegistry.create[IO]
      handler   = new RequestHandler[IO](makeServer((_, _) => IO.pure(CallToolResponse(Nil, None, false))), sink, registry)
      cancelMsg = Message.Notification(
        "2.0",
        "notifications/cancelled",
        Some(Json.obj("requestId" -> 999.asJson))
      )
      result <- handler.handle(cancelMsg)
    yield assertEquals(result, None)
  }

  test("successful request returns a normal response and unregisters the id") {
    for
      sink     <- NotificationSink.create[IO].allocated.map(_._1)
      registry <- CancellationRegistry.create[IO]
      handler   = new RequestHandler[IO](
        makeServer((_, _) => IO.pure(CallToolResponse(Nil, None, false))),
        sink,
        registry
      )
      reqId = RequestId.fromLong(10L)
      requestMsg = Message.Request(
        "2.0",
        reqId,
        "tools/call",
        Some(Json.obj("name" -> "x".asJson, "arguments" -> Json.obj()))
      )
      response <- handler.handle(requestMsg)
      active   <- registry.isActive(reqId)
    yield
      assertEquals(active, false)
      response match
        case Some(Message.Response(_, _, Some(_), None)) => assert(true)
        case other                                        => fail(s"expected success response, got: $other")
  }
