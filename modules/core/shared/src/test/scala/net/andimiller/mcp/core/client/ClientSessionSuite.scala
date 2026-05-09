package net.andimiller.mcp.core.client

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.ErrorCode
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId
import net.andimiller.mcp.core.transport.MessageChannel

import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

class ClientSessionSuite extends CatsEffectSuite:

  /** A pair of [[MessageChannel]]s wired together by two queues — `client` writes go to `server.incoming` and
    * vice-versa. Lets us drive client-server round-trips entirely in-memory.
    */
  private def pairedChannels: IO[(MessageChannel[IO], MessageChannel[IO])] =
    for
      cToS <- Queue.unbounded[IO, Message]
      sToC <- Queue.unbounded[IO, Message]
    yield
      val client = MessageChannel[IO](
        incomingStream = Stream.fromQueueUnterminated(sToC),
        sendFn = cToS.offer,
        closeFn = IO.unit
      )
      val server = MessageChannel[IO](
        incomingStream = Stream.fromQueueUnterminated(cToS),
        sendFn = sToC.offer,
        closeFn = IO.unit
      )
      (client, server)

  /** Start a server-side handler stream as a managed background fiber so the test body doesn't have to remember to
    * cancel it.
    */
  private def runServer(handler: Message => IO[Unit], channel: MessageChannel[IO]): Resource[IO, Unit] =
    Resource
      .make(channel.incoming.evalMap(handler).compile.drain.start)(_.cancel)
      .void

  /** A minimal fake server covering the methods exercised in this suite. */
  private def fakeServerHandler(channel: MessageChannel[IO]): Message => IO[Unit] = {
    case Message.Request(_, id, "initialize", _) =>
      val resp = InitializeResponse(
        protocolVersion = "2025-11-25",
        capabilities = ServerCapabilities.empty.copy(tools = Some(ToolCapabilities(Some(false)))),
        serverInfo = Implementation("fake-server", "0.0.1")
      )
      channel.send(Message.response(id, resp.asJson))

    case Message.Request(_, id, "tools/list", _) =>
      val resp = ListToolsResponse(
        tools = List(
          ToolDefinition(
            name = "echo",
            description = "Echo back input",
            inputSchema = Json.obj("type" -> "object".asJson)
          )
        )
      )
      channel.send(Message.response(id, resp.asJson))

    case Message.Request(_, id, "tools/call", params) =>
      val callName = params.flatMap(_.hcursor.get[String]("name").toOption).getOrElse("")
      val resp     = CallToolResponse(
        content = List(Content.Text(s"called $callName")),
        structuredContent = None,
        isError = false
      )
      channel.send(Message.response(id, resp.asJson))

    case Message.Request(_, id, "ping", _) =>
      channel.send(Message.response(id, Json.obj()))

    case Message.Request(_, id, method, _) =>
      channel.send(Message.errorResponse(id, JsonRpcError.methodNotFound(method)))

    case _ =>
      IO.unit
  }

  test("initialize handshake yields an McpClient with the negotiated server info") {
    for
      channels <- pairedChannels
      clientCh  = channels._1
      serverCh  = channels._2
      client   <- runServer(fakeServerHandler(serverCh), serverCh).use { _ =>
                  ClientSession.resource[IO](clientCh).use { uninit =>
                    uninit.initialize(Implementation("test-client", "0.1.0"))
                  }
                }
    yield
      assertEquals(client.serverInfo.name, "fake-server")
      assertEquals(client.serverInfo.version, "0.0.1")
      assertEquals(client.protocolVersion, "2025-11-25")
      assertEquals(client.serverCapabilities.tools.flatMap(_.listChanged), Some(false))
  }

  test("listTools and callTool round-trip through the fake server") {
    for
      channels <- pairedChannels
      clientCh  = channels._1
      serverCh  = channels._2
      out      <- runServer(fakeServerHandler(serverCh), serverCh).use { _ =>
               ClientSession.resource[IO](clientCh).use { uninit =>
                 for
                   client <- uninit.initialize(Implementation("test", "0.0"))
                   tools  <- client.listTools()
                   res    <- client.callTool("echo", Json.obj("msg" -> "hi".asJson))
                   _      <- client.ping()
                 yield (tools, res)
               }
             }
    yield
      val (tools, res) = out
      assertEquals(tools.tools.map(_.name), List("echo"))
      assertEquals(res.isError, false)
      assertEquals(res.content.headOption, Some(Content.Text("called echo")))
  }

  test("server notifications surface on McpClient.notifications") {
    for
      channels <- pairedChannels
      clientCh  = channels._1
      serverCh  = channels._2
      out      <- runServer(fakeServerHandler(serverCh), serverCh).use { _ =>
               ClientSession.resource[IO](clientCh).use { uninit =>
                 for
                   client   <- uninit.initialize(Implementation("test", "0.0"))
                   notifFib <- client.notifications.take(1).compile.toList.start
                   _        <- IO.sleep(50.millis)
                   _        <- serverCh.send(
                          Message.notification(
                            "notifications/tools/list_changed",
                            Some(Json.obj("changed" -> true.asJson))
                          )
                        )
                   ns <- notifFib.joinWithNever.timeout(2.seconds)
                 yield ns
               }
             }
    yield
      assertEquals(out.size, 1)
      assertEquals(out.head.method, "notifications/tools/list_changed")
  }

  test("server-initiated request is dispatched to the ClientHandler") {
    val handler = ClientHandler.of[IO](
      requests = { case "roots/list" =>
        (_: RequestId, _: Option[Json]) =>
          IO.pure(
            Right(
              Json.obj(
                "roots" -> Json.arr(Json.obj("uri" -> "file:///tmp".asJson, "name" -> "tmp".asJson))
              )
            )
          )
      }
    )
    for
      channels <- pairedChannels
      clientCh  = channels._1
      serverCh  = channels._2
      capture  <- Queue.unbounded[IO, Message]
      // Server: respond to initialize, ignore the `notifications/initialized`, capture everything else.
      serverFn = (m: Message) =>
                   m match
                     case Message.Request(_, id, "initialize", _) =>
                       val resp = InitializeResponse(
                         protocolVersion = "2025-11-25",
                         capabilities = ServerCapabilities.empty,
                         serverInfo = Implementation("fake", "0")
                       )
                       serverCh.send(Message.response(id, resp.asJson))
                     case _: Message.Notification =>
                       IO.unit
                     case other =>
                       capture.offer(other)
      received <- runServer(serverFn, serverCh).use { _ =>
                    ClientSession.resource[IO](clientCh, handler).use { uninit =>
                      for
                        _ <- uninit.initialize(Implementation("test", "0.0"))
                        _ <- serverCh.send(
                               Message.request(RequestId.fromString("from-server-1"), "roots/list", None)
                             )
                        m <- capture.take.timeout(2.seconds)
                      yield m
                    }
                  }
    yield received match
      case Message.Response(_, id, Some(json), None) =>
        assertEquals(id, RequestId.fromString("from-server-1"))
        assertEquals(json.hcursor.get[List[Json]]("roots").map(_.size), Right(1))
      case other =>
        fail(s"expected response, got $other")
  }

  test("server-initiated request with no matching handler returns MethodNotFound") {
    for
      channels <- pairedChannels
      clientCh  = channels._1
      serverCh  = channels._2
      capture  <- Queue.unbounded[IO, Message]
      serverFn  = (m: Message) =>
                   m match
                     case Message.Request(_, id, "initialize", _) =>
                       val resp = InitializeResponse(
                         protocolVersion = "2025-11-25",
                         capabilities = ServerCapabilities.empty,
                         serverInfo = Implementation("fake", "0")
                       )
                       serverCh.send(Message.response(id, resp.asJson))
                     case _: Message.Notification =>
                       IO.unit
                     case other =>
                       capture.offer(other)
      received <- runServer(serverFn, serverCh).use { _ =>
                    ClientSession.resource[IO](clientCh).use { uninit =>
                      for
                        _ <- uninit.initialize(Implementation("test", "0.0"))
                        _ <- serverCh.send(
                               Message.request(RequestId.fromString("srv-x"), "sampling/createMessage", None)
                             )
                        m <- capture.take.timeout(2.seconds)
                      yield m
                    }
                  }
    yield received match
      case Message.Response(_, _, _, Some(err)) =>
        assertEquals(err.code, ErrorCode.MethodNotFound)
      case other =>
        fail(s"expected error response, got $other")
  }
