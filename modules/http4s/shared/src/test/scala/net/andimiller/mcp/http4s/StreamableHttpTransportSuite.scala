package net.andimiller.mcp.http4s

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import io.circe.parser.decode
import munit.CatsEffectSuite
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.{Message, RequestId}
import net.andimiller.mcp.core.server.*
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.ci.*

import scala.concurrent.duration.*

class StreamableHttpTransportSuite extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 10.seconds

  private val mcpSessionId = ci"Mcp-Session-Id"

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

  private def buildServer(tools: List[Tool.Resolved[IO]] = List(echoTool)): (String, SessionContext[IO]) => IO[Server[IO]] =
    (_, _) =>
      DefaultServer[IO](
        info             = Implementation("t", "0"),
        capabilities     = ServerCapabilities(),
        toolHandlers     = tools
      ).widen[Server[IO]]

  private def postJson(routes: HttpRoutes[IO], body: Json, sessionId: Option[String] = None): IO[Response[IO]] =
    val base    = Request[IO](Method.POST, uri"/mcp")
                    .withEntity(body.noSpaces)
                    .withContentType(`Content-Type`(MediaType.application.json))
    val withSid = sessionId.fold(base)(sid => base.putHeaders(Header.Raw(mcpSessionId, sid)))
    routes.run(withSid).getOrElseF(IO.raiseError(new Exception("no route matched")))

  private def deleteWithSid(routes: HttpRoutes[IO], sid: String): IO[Response[IO]] =
    routes.run(Request[IO](Method.DELETE, uri"/mcp").putHeaders(Header.Raw(mcpSessionId, sid)))
      .getOrElseF(IO.raiseError(new Exception("no route matched")))

  private def getWithSid(routes: HttpRoutes[IO], sid: Option[String]): IO[Response[IO]] =
    val base = Request[IO](Method.GET, uri"/mcp")
    val req  = sid.fold(base)(s => base.putHeaders(Header.Raw(mcpSessionId, s)))
    routes.run(req).getOrElseF(IO.raiseError(new Exception("no route matched")))

  private def initBody(id: Long = 1L): Json =
    Message.request(
      RequestId.fromLong(id),
      "initialize",
      Some(InitializeRequest("2025-11-25", ClientCapabilities.empty, Implementation("c", "1")).asJson)
    ).asJson

  private def toolsCallBody(id: Long, tool: String = "echo"): Json =
    Message.request(
      RequestId.fromLong(id),
      "tools/call",
      Some(CallToolRequest(tool, Json.obj()).asJson)
    ).asJson

  private def initSession(routes: HttpRoutes[IO]): IO[String] =
    for
      resp <- postJson(routes, initBody(1L))
      _    <- IO.raiseUnless(resp.status == Status.Ok)(new Exception(s"init failed: ${resp.status}"))
      sid  <- IO.fromOption(resp.headers.get(mcpSessionId).map(_.head.value))(
                new Exception("missing Mcp-Session-Id header")
              )
    yield sid

  test("POST /mcp initialize without session: returns 200 and an Mcp-Session-Id header") {
    StreamableHttpTransport.routes[IO](buildServer()).use { routes =>
      for
        resp <- postJson(routes, initBody())
      yield
        assertEquals(resp.status, Status.Ok)
        assert(resp.headers.get(mcpSessionId).isDefined, "expected Mcp-Session-Id header")
    }
  }

  test("POST /mcp tools/call with valid session id returns the tool response") {
    StreamableHttpTransport.routes[IO](buildServer()).use { routes =>
      for
        sid  <- initSession(routes)
        resp <- postJson(routes, toolsCallBody(2L), Some(sid))
        body <- resp.bodyText.compile.string
      yield
        assertEquals(resp.status, Status.Ok)
        val msg = decode[Message](body).fold(err => fail(s"bad body: $err"), identity)
        msg match
          case Message.Response(_, _, Some(_), None) => ()
          case other                                 => fail(s"expected success Response, got $other")
    }
  }

  test("POST /mcp with unknown session id: 404") {
    StreamableHttpTransport.routes[IO](buildServer()).use { routes =>
      for
        resp <- postJson(routes, toolsCallBody(1L), Some("does-not-exist"))
      yield assertEquals(resp.status, Status.NotFound)
    }
  }

  test("POST /mcp with a notification: 202 Accepted") {
    StreamableHttpTransport.routes[IO](buildServer()).use { routes =>
      for
        sid  <- initSession(routes)
        resp <- postJson(routes,
                         Message.notification("notifications/initialized", None).asJson,
                         Some(sid))
      yield assertEquals(resp.status, Status.Accepted)
    }
  }

  test("GET /mcp without Mcp-Session-Id header: 400") {
    StreamableHttpTransport.routes[IO](buildServer()).use { routes =>
      for
        resp <- getWithSid(routes, None)
      yield assertEquals(resp.status, Status.BadRequest)
    }
  }

  test("DELETE /mcp removes the session; subsequent POST is 404") {
    StreamableHttpTransport.routes[IO](buildServer()).use { routes =>
      for
        sid    <- initSession(routes)
        delResp <- deleteWithSid(routes, sid)
        post    <- postJson(routes, toolsCallBody(2L), Some(sid))
      yield
        assertEquals(delResp.status, Status.Ok)
        assertEquals(post.status, Status.NotFound)
    }
  }

  test("DELETE /mcp cancels in-flight requests in the deleted session") {
    Deferred[IO, Unit].flatMap { gate =>
      StreamableHttpTransport.routes[IO](buildServer(tools = List(gatedTool(gate)))).use { routes =>
        for
          sid     <- initSession(routes)
          callFib <- postJson(routes, toolsCallBody(5L, "slow"), Some(sid)).start
          _       <- IO.sleep(150.millis)
          delResp <- deleteWithSid(routes, sid)
          callResp <- callFib.joinWithNever
        yield
          assertEquals(delResp.status, Status.Ok)
          assertEquals(callResp.status, Status.Accepted, "cancelled tool call should produce Accepted, not a response body")
      }
    }
  }

  test("Cross-session isolation: cancellation in session A does not affect session B") {
    Deferred[IO, Unit].flatMap { gateA =>
      Deferred[IO, Unit].flatMap { gateB =>
        StreamableHttpTransport.routes[IO](buildServer(tools = List(gatedTool(gateA)))).use { routesA =>
          StreamableHttpTransport.routes[IO](buildServer(tools = List(gatedTool(gateB)))).use { routesB =>
            for
              sidA   <- initSession(routesA)
              sidB   <- initSession(routesB)
              fibA   <- postJson(routesA, toolsCallBody(7L, "slow"), Some(sidA)).start
              fibB   <- postJson(routesB, toolsCallBody(7L, "slow"), Some(sidB)).start
              _      <- IO.sleep(150.millis)
              _      <- deleteWithSid(routesA, sidA)
              respA  <- fibA.joinWithNever
              _      <- gateB.complete(())
              respB  <- fibB.joinWithNever
            yield
              assertEquals(respA.status, Status.Accepted, "A's cancelled call should be Accepted")
              assertEquals(respB.status, Status.Ok, "B's call should still complete normally")
          }
        }
      }
    }
  }
