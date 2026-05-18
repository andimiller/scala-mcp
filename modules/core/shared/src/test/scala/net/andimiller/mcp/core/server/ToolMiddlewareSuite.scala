package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.Ref

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content

import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

class ToolMiddlewareSuite extends CatsEffectSuite:

  private def emptyArgs: Json = Json.obj()

  /** A tool whose handler just returns whatever response value we give it. */
  private def echoTool(response: CallToolResponse): Tool[IO, Unit] =
    new Tool[IO, Unit]:
      def name                                                          = "echo"
      def description                                                   = "echo"
      def inputSchema                                                   = Json.obj()
      def outputSchema                                                  = None
      def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] = IO.pure(response)

  /** A middleware that records when its request/response halves fire, to verify composition order. */
  private def tracingMw(label: String, log: Ref[IO, List[String]]): ToolMiddleware[IO, Unit] = handler =>
    context =>
      log.update(_ :+ s"$label:request") *>
        handler(context).flatTap(_ => log.update(_ :+ s"$label:response"))

  test("server-wide middleware can read request._meta and decorate the response") {
    val auditMw: ToolMiddleware[IO, Unit] = handler =>
      ctx =>
        val token = ctx.request._meta.flatMap(_("audit/tier").flatMap(_.asString)).getOrElse("none")
        handler(ctx).map(_.copy(_meta = Some(JsonObject("audit/observed-tier" -> token.asJson))))

    for
      server <- DefaultServer[IO](
                  info = Implementation("t", "0"),
                  capabilities = ServerCapabilities(),
                  toolHandlers = List(echoTool(CallToolResponse(content = List(Content.Text("x"))))),
                  toolMiddlewares = List(auditMw)
                )
      response <- server.callTool(
                    CallToolRequest(
                      name = "echo",
                      arguments = emptyArgs,
                      _meta = Some(JsonObject("audit/tier" -> "high".asJson))
                    )
                  )
    yield assertEquals(
      response._meta,
      Some(JsonObject("audit/observed-tier" -> "high".asJson))
    )
  }

  test("middleware sees ctx.sessionId when the server is constructed with one") {
    val captured: Ref[IO, Option[String]]         = Ref.unsafe(None)
    val sessionCapturer: ToolMiddleware[IO, Unit] = handler => ctx => captured.set(ctx.sessionId) *> handler(ctx)

    for
      server <- DefaultServer[IO](
                  info = Implementation("t", "0"),
                  capabilities = ServerCapabilities(),
                  toolHandlers = List(echoTool(CallToolResponse(content = List(Content.Text("x"))))),
                  toolMiddlewares = List(sessionCapturer),
                  sessionId = Some("session-abc")
                )
      _    <- server.callTool(CallToolRequest("echo", emptyArgs))
      seen <- captured.get
    yield assertEquals(seen, Some("session-abc"))
  }

  test("middleware sees ctx.resolved with the tool's metadata") {
    val captured: Ref[IO, Option[String]]       = Ref.unsafe(None)
    val toolInspector: ToolMiddleware[IO, Unit] = handler => ctx => captured.set(ctx.resolved.title) *> handler(ctx)

    val tool = new Tool[IO, Unit]:
      def name                                                          = "echo"
      def description                                                   = "echo"
      def inputSchema                                                   = Json.obj()
      def outputSchema                                                  = None
      override val title                                                = Some("Echo tool")
      def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text("ok"))))

    for
      server <- DefaultServer[IO](
                  info = Implementation("t", "0"),
                  capabilities = ServerCapabilities(),
                  toolHandlers = List(tool),
                  toolMiddlewares = List(toolInspector)
                )
      _    <- server.callTool(CallToolRequest("echo", emptyArgs))
      seen <- captured.get
    yield assertEquals(seen, Some("Echo tool"))
  }

  test("middleware sees ctx.ctx with the per-session Ctx value") {
    val captured: Ref[IO, Option[String]]       = Ref.unsafe(None)
    val ctxCapturer: ToolMiddleware[IO, String] = handler => ctx => captured.set(Some(ctx.ctx)) *> handler(ctx)

    val tool = new Tool[IO, String]:
      def name                                                            = "echo"
      def description                                                     = "echo"
      def inputSchema                                                     = Json.obj()
      def outputSchema                                                    = None
      def handle(call: ToolCallContext[IO, String]): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text("ok"))))

    for
      server <- DefaultServer[IO, String](
                  info = Implementation("t", "0"),
                  capabilities = ServerCapabilities(),
                  ctx = "tenant-42",
                  toolHandlers = List(tool),
                  resourceHandlers = Nil,
                  resourceTemplateHandlers = Nil,
                  promptHandlers = Nil,
                  toolMiddlewares = List(ctxCapturer),
                  sessionId = None
                )
      _    <- server.callTool(CallToolRequest("echo", emptyArgs))
      seen <- captured.get
    yield assertEquals(seen, Some("tenant-42"))
  }

  test("server-wide middlewares compose: first registered = outermost (request flows outer→inner)") {
    for
      log    <- Ref.of[IO, List[String]](Nil)
      server <- DefaultServer[IO](
                  info = Implementation("t", "0"),
                  capabilities = ServerCapabilities(),
                  toolHandlers = List(echoTool(CallToolResponse(content = List(Content.Text("x"))))),
                  toolMiddlewares = List(tracingMw("outer", log), tracingMw("inner", log))
                )
      _      <- server.callTool(CallToolRequest("echo", emptyArgs))
      events <- log.get
    yield assertEquals(
      events,
      List("outer:request", "inner:request", "inner:response", "outer:response")
    )
  }

  test("per-tool middleware composes INSIDE server-wide middleware") {
    for
      log        <- Ref.of[IO, List[String]](Nil)
      perTool     = tracingMw("per-tool", log)
      handler     = echoTool(CallToolResponse(content = List(Content.Text("x"))))
      withPerTool = handler.withMiddleware(perTool)
      server     <- DefaultServer[IO](
                  info = Implementation("t", "0"),
                  capabilities = ServerCapabilities(),
                  toolHandlers = List(withPerTool),
                  toolMiddlewares = List(tracingMw("server", log))
                )
      _      <- server.callTool(CallToolRequest("echo", emptyArgs))
      events <- log.get
    yield assertEquals(
      events,
      List("server:request", "per-tool:request", "per-tool:response", "server:response")
    )
  }

  test("ToolMiddleware.composeAll on an empty list is identity") {
    val handler: ToolHandler[IO, Unit] =
      ctx => IO.pure(CallToolResponse(content = List(Content.Text(ctx.request.name))))
    val composed = ToolMiddleware.composeAll[IO, Unit](Nil)(handler)
    val tool     = new Tool[IO, Unit]:
      def name                                                          = "x"
      def description                                                   = "x"
      def inputSchema                                                   = Json.obj()
      def outputSchema                                                  = None
      def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text("x"))))
    composed(ToolCallContext(CallToolRequest("x", emptyArgs), None, tool, ())).map { resp =>
      assertEquals(resp.content.head, Content.Text("x"))
    }
  }
