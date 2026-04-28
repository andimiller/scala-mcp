package net.andimiller.mcp.core.server

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content

class ServerBuilderSuite extends CatsEffectSuite:

  private def stubTool(n: String): Tool.Resolved[IO] =
    new Tool.Resolved[IO]:
      val name                                          = n
      val description                                   = s"tool $n"
      val inputSchema                                   = Json.obj()
      val outputSchema                                  = None
      def handle(arguments: Json): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text(n)), None, false))

  private def stubResource(suffix: String): McpResource.Resolved[IO] =
    new McpResource.Resolved[IO]:
      val uri                         = "stub://" + suffix
      val name                        = suffix
      val description                 = None
      val mimeType                    = None
      def read(): IO[ResourceContent] =
        IO.pure(ResourceContent.text(uri, "x", None))

  private def stubPrompt(n: String): Prompt.Resolved[IO] =
    new Prompt.Resolved[IO]:
      val name                                                     = n
      val description                                              = None
      val arguments                                                = Nil
      def get(arguments: Map[String, Json]): IO[GetPromptResponse] =
        IO.pure(GetPromptResponse(None, Nil))

  test("empty builder builds a Server with empty lists and no capabilities") {
    for
      server <- ServerBuilder[IO]("s", "1").build
      tools  <- server.listTools(ListToolsRequest())
      res    <- server.listResources(ListResourcesRequest())
      pr     <- server.listPrompts(ListPromptsRequest())
    yield
      assertEquals(tools.tools, Nil)
      assertEquals(res.resources, Nil)
      assertEquals(pr.prompts, Nil)
      assertEquals(server.capabilities, ServerCapabilities())
      assertEquals(server.info, Implementation("s", "1"))
  }

  test("withTool adds the handler and auto-enables tools capability") {
    for
      server <- ServerBuilder[IO]("s", "1").withTool(stubTool("greet")).build
      tools  <- server.listTools(ListToolsRequest())
    yield
      assertEquals(tools.tools.map(_.name), List("greet"))
      assertEquals(server.capabilities.tools, Some(ToolCapabilities()))
  }

  test("withResource adds the handler and auto-enables resources capability") {
    for
      server <- ServerBuilder[IO]("s", "1").withResource(stubResource("a")).build
      res    <- server.listResources(ListResourcesRequest())
    yield
      assertEquals(res.resources.map(_.uri), List("stub://a"))
      assertEquals(server.capabilities.resources, Some(ResourceCapabilities()))
  }

  test("withPrompt adds the handler and auto-enables prompts capability") {
    for
      server <- ServerBuilder[IO]("s", "1").withPrompt(stubPrompt("p")).build
      pr     <- server.listPrompts(ListPromptsRequest())
    yield
      assertEquals(pr.prompts.map(_.name), List("p"))
      assertEquals(server.capabilities.prompts, Some(PromptCapabilities()))
  }

  test("enableResourceSubscriptions sets subscribe on resources capability") {
    for server <- ServerBuilder[IO]("s", "1")
                    .withResource(stubResource("a"))
                    .enableResourceSubscriptions
                    .build
    yield assertEquals(
      server.capabilities.resources,
      Some(ResourceCapabilities(subscribe = Some(true)))
    )
  }

  test("withTools accumulates multiple tools in order") {
    for
      server <- ServerBuilder[IO]("s", "1").withTools(stubTool("a"), stubTool("b")).build
      tools  <- server.listTools(ListToolsRequest())
    yield assertEquals(tools.tools.map(_.name).toSet, Set("a", "b"))
  }

  test("duplicate tool names: the later registration silently wins") {
    for
      server <- ServerBuilder[IO]("s", "1").withTool(stubTool("a")).withTool(stubTool("a")).build
      tools  <- server.listTools(ListToolsRequest())
    yield assertEquals(tools.tools.map(_.name), List("a"))
  }
