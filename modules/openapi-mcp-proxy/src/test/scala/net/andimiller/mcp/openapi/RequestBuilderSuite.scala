package net.andimiller.mcp.openapi

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.circe.*
import org.http4s.{EntityEncoder, HttpApp, Method, Request, Response, Status}
import org.http4s.client.Client

class RequestBuilderSuite extends CatsEffectSuite:

  private def recordingClient(
    captured: Ref[IO, List[Request[IO]]],
    body: Json = Json.obj("ok" -> true.asJson)
  ): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      req.bodyText.compile.string.flatMap { bodyText =>
        captured.update(_ :+ req.withEntity(bodyText)) *>
          IO.pure(Response[IO](Status.Ok).withEntity(body.asJson))
      }
    })

  test("path param substituted into URI") {
    val op = ResolvedOperation(
      pathParams = List(ResolvedParam("id", required = true)),
      queryParams = Nil,
      headerParams = Nil,
      hasBody = false
    )
    for
      captured <- Ref.of[IO, List[Request[IO]]](Nil)
      client    = recordingClient(captured)
      _        <- RequestBuilder.execute(
                    client, "https://api.example.com",
                    Method.GET, "/users/{id}", op,
                    Json.obj("id" -> "42".asJson)
                  )
      reqs     <- captured.get
    yield
      assertEquals(reqs.length, 1)
      assertEquals(reqs.head.uri.path.toString, "/users/42")
  }

  test("query param appended to URI") {
    val op = ResolvedOperation(
      pathParams = Nil,
      queryParams = List(ResolvedParam("limit", required = false)),
      headerParams = Nil,
      hasBody = false
    )
    for
      captured <- Ref.of[IO, List[Request[IO]]](Nil)
      _        <- RequestBuilder.execute(
                    recordingClient(captured), "https://api.example.com",
                    Method.GET, "/items", op,
                    Json.obj("limit" -> 10.asJson)
                  )
      reqs     <- captured.get
    yield assertEquals(reqs.head.uri.query.params.get("limit"), Some("10"))
  }

  test("query param omitted when arg missing or null") {
    val op = ResolvedOperation(
      pathParams = Nil,
      queryParams = List(ResolvedParam("limit", required = false)),
      headerParams = Nil,
      hasBody = false
    )
    for
      captured <- Ref.of[IO, List[Request[IO]]](Nil)
      _        <- RequestBuilder.execute(
                    recordingClient(captured), "https://api.example.com",
                    Method.GET, "/items", op,
                    Json.obj()
                  )
      reqs     <- captured.get
    yield assertEquals(reqs.head.uri.query.params.get("limit"), None)
  }

  test("header param set from arguments") {
    val op = ResolvedOperation(
      pathParams = Nil,
      queryParams = Nil,
      headerParams = List(ResolvedParam("X-Trace-Id", required = false)),
      hasBody = false
    )
    for
      captured <- Ref.of[IO, List[Request[IO]]](Nil)
      _        <- RequestBuilder.execute(
                    recordingClient(captured), "https://api.example.com",
                    Method.GET, "/items", op,
                    Json.obj("X-Trace-Id" -> "abc".asJson)
                  )
      reqs     <- captured.get
    yield assertEquals(
      reqs.head.headers.get(org.typelevel.ci.CIString("X-Trace-Id")).map(_.head.value),
      Some("abc")
    )
  }

  test("POST with hasBody=true serialises args.body as JSON entity") {
    val op = ResolvedOperation(Nil, Nil, Nil, hasBody = true)
    for
      captured <- Ref.of[IO, List[Request[IO]]](Nil)
      _        <- RequestBuilder.execute(
                    recordingClient(captured), "https://api.example.com",
                    Method.POST, "/items", op,
                    Json.obj("body" -> Json.obj("name" -> "x".asJson))
                  )
      reqs     <- captured.get
      body     <- reqs.head.bodyText.compile.string
    yield assertEquals(io.circe.parser.parse(body).toOption, Some(Json.obj("name" -> "x".asJson)))
  }

  test("GET with hasBody=false sends no body") {
    val op = ResolvedOperation(Nil, Nil, Nil, hasBody = false)
    for
      captured <- Ref.of[IO, List[Request[IO]]](Nil)
      _        <- RequestBuilder.execute(
                    recordingClient(captured), "https://api.example.com",
                    Method.GET, "/items", op,
                    Json.obj()
                  )
      reqs     <- captured.get
      body     <- reqs.head.bodyText.compile.string
    yield assertEquals(body, "")
  }

  test("non-2xx HTTP response surfaces as ToolResult.Error") {
    import net.andimiller.mcp.core.protocol.ToolResult
    val op = ResolvedOperation(Nil, Nil, Nil, hasBody = false)
    val errClient = Client.fromHttpApp(HttpApp[IO] { _ =>
      IO.pure(Response[IO](Status.NotFound).withEntity("nope"))
    })
    RequestBuilder.execute(errClient, "https://api.example.com", Method.GET, "/x", op, Json.obj()).map {
      case ToolResult.Error(msg) => assert(msg.contains("404"), msg)
      case other                 => fail(s"expected Error, got $other")
    }
  }

  test("array response is wrapped in {items: [...]}") {
    import net.andimiller.mcp.core.protocol.ToolResult
    val op = ResolvedOperation(Nil, Nil, Nil, hasBody = false)
    val arrayClient = Client.fromHttpApp(HttpApp[IO] { _ =>
      IO.pure(Response[IO](Status.Ok).withEntity(Json.arr(Json.fromInt(1), Json.fromInt(2))))
    })
    RequestBuilder.execute(arrayClient, "https://api.example.com", Method.GET, "/x", op, Json.obj()).map {
      case ToolResult.Raw(_, Some(structured), _) =>
        assertEquals(structured, Json.obj("items" -> Json.arr(Json.fromInt(1), Json.fromInt(2))))
      case other => fail(s"expected Raw with structuredContent, got $other")
    }
  }
