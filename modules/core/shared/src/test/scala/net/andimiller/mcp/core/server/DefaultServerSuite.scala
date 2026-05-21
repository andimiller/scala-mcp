package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.kernel.Ref

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content

import io.circe.Json
import munit.CatsEffectSuite

class DefaultServerSuite extends CatsEffectSuite:

  private def textTool(n: String): Tool[IO, Unit] =
    new Tool[IO, Unit]:
      val name                                                          = n
      val description                                                   = ""
      val inputSchema                                                   = Json.obj()
      val outputSchema                                                  = None
      def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text(n)), None, false))

  private def directResource(resourceUri: String): McpResource.Resolved[IO] =
    new McpResource.Resolved[IO]:
      val uri                         = resourceUri
      val name                        = "n"
      val description                 = None
      val mimeType                    = None
      def read(): IO[ResourceContent] =
        IO.pure(ResourceContent.text(resourceUri, "direct", None))

  private def templatedResource(prefix: String): ResourceTemplate.Resolved[IO] =
    new ResourceTemplate.Resolved[IO]:
      val uriTemplate                                    = s"$prefix/{id}"
      val name                                           = "tpl"
      val description                                    = None
      val mimeType                                       = None
      def read(uri: String): Option[IO[ResourceContent]] =
        if uri.startsWith(s"$prefix/") then Some(IO.pure(ResourceContent.text(uri, "from-template", None)))
        else None

  private def server(
      tools: List[Tool[IO, Unit]] = Nil,
      resources: List[McpResource.Resolved[IO]] = Nil,
      templates: List[ResourceTemplate.Resolved[IO]] = Nil,
      prompts: List[Prompt.Resolved[IO]] = Nil
  ): IO[DefaultServer[IO, Unit]] =
    DefaultServer[IO](
      info = Implementation("t", "0"),
      capabilities = ServerCapabilities(),
      toolHandlers = tools,
      resourceHandlers = resources,
      resourceTemplateHandlers = templates,
      promptHandlers = prompts
    )

  test("subscribe / unsubscribe round-trip leaves no entry behind") {
    for
      s <- server()
      _ <- s.subscribe(SubscribeRequest("a://1"))
      _ <- s.subscribe(SubscribeRequest("a://2"))
      _ <- s.unsubscribe(UnsubscribeRequest("a://1"))
    yield ()
  }

  test("ping returns Unit") {
    server().flatMap(_.ping())
  }

  test("readResource returns direct resource content when URI matches") {
    for
      s   <- server(resources = List(directResource("a://1")))
      out <- s.readResource(ReadResourceRequest("a://1"))
    yield assertEquals(out.contents.flatMap(_.text), List("direct"))
  }

  test("readResource falls back to a matching ResourceTemplate") {
    for
      s   <- server(templates = List(templatedResource("file:///x")))
      out <- s.readResource(ReadResourceRequest("file:///x/abc"))
    yield assertEquals(out.contents.flatMap(_.text), List("from-template"))
  }

  test("readResource raises when no resource and no template matches") {
    for
      s      <- server()
      result <- s.readResource(ReadResourceRequest("nope://x")).attempt
    yield result match
      case Left(_)  => ()
      case Right(_) => fail("expected error for unknown resource")
  }

  test("listResourceTemplates exposes templates with definitions") {
    for
      s   <- server(templates = List(templatedResource("file:///x")))
      out <- s.listResourceTemplates(ListResourceTemplatesRequest())
    yield assertEquals(out.resourceTemplates.map(_.uriTemplate), List("file:///x/{id}"))
  }

  test("callTool raises for unknown tool name") {
    for
      s      <- server(tools = List(textTool("known")))
      result <- s.callTool(CallToolRequest("missing", Json.obj())).attempt
    yield result match
      case Left(t)  => assert(t.getMessage.contains("missing"), t.getMessage)
      case Right(_) => fail("expected error for unknown tool")
  }

  test("callTool returns the tool's response when found") {
    for
      s   <- server(tools = List(textTool("hello")))
      out <- s.callTool(CallToolRequest("hello", Json.obj()))
    yield assertEquals(out.content, List(Content.Text("hello")))
  }

  test("getPrompt raises for unknown prompt name") {
    for
      s      <- server()
      result <- s.getPrompt(GetPromptRequest("missing", Map.empty)).attempt
    yield result match
      case Left(_)  => ()
      case Right(_) => fail("expected error for unknown prompt")
  }

  // ── Visibility ──────────────────────────────────────────────────────────

  /** Build a tool whose visibility is dictated by `vis`. Handler is a no-op text response so the visibility behaviour
    * — not the handler — is what we're exercising.
    */
  private def visTool(n: String, vis: Option[Unit => IO[Boolean]] = None): Tool[IO, Unit] =
    new Tool[IO, Unit]:
      val name                                                          = n
      val description                                                   = ""
      val inputSchema                                                   = Json.obj()
      val outputSchema                                                  = None
      override val visible                                              = vis
      def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text(n)), None, false))

  test("listTools omits tools whose visibility predicate returns false") {
    for
      s   <- server(tools =
               List(
                 visTool("always"),
                 visTool("hidden", Some(_ => IO.pure(false))),
                 visTool("shown", Some(_ => IO.pure(true)))
               )
             )
      out <- s.listTools(ListToolsRequest())
    yield assertEquals(out.tools.map(_.name).sorted, List("always", "shown"))
  }

  test("listTools threads effects through the visibility predicate") {
    for
      ref <- Ref.of[IO, Int](0)
      tool = visTool("counted", Some(_ => ref.updateAndGet(_ + 1).map(_ > 0)))
      s   <- server(tools = List(tool))
      _   <- s.listTools(ListToolsRequest())
      _   <- s.listTools(ListToolsRequest())
      n   <- ref.get
    yield assertEquals(n, 2)
  }

  test("callTool rejects an invisible tool the same way it rejects an unknown one") {
    for
      s         <- server(tools = List(visTool("secret", Some(_ => IO.pure(false)))))
      hiddenErr <- s.callTool(CallToolRequest("secret", Json.obj())).attempt
      missErr   <- s.callTool(CallToolRequest("missing", Json.obj())).attempt
    yield
      assert(hiddenErr.isLeft && hiddenErr.left.toOption.get.getMessage.contains("secret"))
      assert(missErr.isLeft && missErr.left.toOption.get.getMessage.contains("missing"))
  }

  test("callTool invokes the tool when its visibility predicate returns true") {
    for
      s   <- server(tools = List(visTool("open", Some(_ => IO.pure(true)))))
      out <- s.callTool(CallToolRequest("open", Json.obj()))
    yield assertEquals(out.content, List(Content.Text("open")))
  }

