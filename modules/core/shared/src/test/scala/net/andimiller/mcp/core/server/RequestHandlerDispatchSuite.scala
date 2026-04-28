package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.std.Queue
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.ErrorCode
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

class RequestHandlerDispatchSuite extends CatsEffectSuite:

  private def textTool: Tool.Resolved[IO] =
    new Tool.Resolved[IO]:
      val name                                          = "echo"
      val description                                   = "echo"
      val inputSchema                                   = Json.obj()
      val outputSchema                                  = None
      def handle(arguments: Json): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text("ok")), None, false))

  private def staticResource: McpResource.Resolved[IO] =
    new McpResource.Resolved[IO]:
      val uri                         = "x://r"
      val name                        = "r"
      val description                 = None
      val mimeType                    = None
      def read(): IO[ResourceContent] =
        IO.pure(ResourceContent.text(uri, "hi", None))

  private def staticPrompt: Prompt.Resolved[IO] =
    new Prompt.Resolved[IO]:
      val name                                                     = "p"
      val description                                              = None
      val arguments                                                = Nil
      def get(arguments: Map[String, Json]): IO[GetPromptResponse] =
        IO.pure(GetPromptResponse(None, List(PromptMessage.user("hi"))))

  private def buildHandlerAnd(
      tools: List[Tool.Resolved[IO]] = Nil,
      resources: List[McpResource.Resolved[IO]] = Nil,
      prompts: List[Prompt.Resolved[IO]] = Nil
  ): IO[(RequestHandler[IO], ServerRequester[IO])] =
    for
      server <- DefaultServer[IO](
                  info = Implementation("t", "0"),
                  capabilities = ServerCapabilities(),
                  toolHandlers = tools,
                  resourceHandlers = resources,
                  promptHandlers = prompts
                )
      requester <- ServerRequester.create[IO](_ => IO.unit)
      cancel    <- CancellationRegistry.create[IO]
    yield (new RequestHandler[IO](server, requester, cancel), requester)

  private def buildHandler(
      tools: List[Tool.Resolved[IO]] = Nil,
      resources: List[McpResource.Resolved[IO]] = Nil,
      prompts: List[Prompt.Resolved[IO]] = Nil
  ): IO[RequestHandler[IO]] =
    buildHandlerAnd(tools, resources, prompts).map(_._1)

  private def call(handler: RequestHandler[IO], method: String, params: Option[Json] = None, id: Long = 1L) =
    handler.handle(Message.request(RequestId.fromLong(id), method, params))

  test("initialize returns protocolVersion 2025-11-25 and stores client capabilities") {
    val req = InitializeRequest(
      protocolVersion = "2025-11-25",
      capabilities = ClientCapabilities.empty,
      clientInfo = Implementation("c", "1")
    )
    for
      pair          <- buildHandlerAnd()
      (h, requester) = pair
      out           <- call(h, "initialize", Some(req.asJson))
      caps          <- requester.clientCapabilities
    yield
      val resp = out.collect { case Message.Response(_, _, Some(r), _) => r }
      assertEquals(resp.flatMap(_.hcursor.get[String]("protocolVersion").toOption), Some("2025-11-25"))
      assertEquals(caps, Some(ClientCapabilities.empty))
  }

  test("ping returns an empty object") {
    for
      h   <- buildHandler()
      out <- call(h, "ping")
    yield out match
      case Some(Message.Response(_, _, Some(r), None)) => assertEquals(r, Json.obj())
      case other                                       => fail(s"expected empty Response, got $other")
  }

  test("tools/list returns configured tools") {
    for
      h   <- buildHandler(tools = List(textTool))
      out <- call(h, "tools/list")
    yield out match
      case Some(Message.Response(_, _, Some(r), None)) =>
        val names = r.hcursor.downField("tools").as[List[ToolDefinition]].toOption.map(_.map(_.name))
        assertEquals(names, Some(List("echo")))
      case other => fail(s"unexpected $other")
  }

  test("tools/call with valid args returns the tool response") {
    for
      h   <- buildHandler(tools = List(textTool))
      out <- call(h, "tools/call", Some(CallToolRequest("echo", Json.obj()).asJson))
    yield out match
      case Some(Message.Response(_, _, Some(r), None)) =>
        val content = r.hcursor.downField("content").as[List[Content]].toOption
        assertEquals(content, Some(List(Content.Text("ok"))))
      case other => fail(s"unexpected $other")
  }

  test("tools/call without params returns an error response (not a thrown exception)") {
    for
      h   <- buildHandler(tools = List(textTool))
      out <- call(h, "tools/call", None)
    yield out match
      case Some(Message.Response(_, _, None, Some(err))) =>
        assertEquals(err.code, ErrorCode.InternalError)
      case other => fail(s"unexpected $other")
  }

  test("tools/call for unknown tool name returns an internalError response") {
    for
      h   <- buildHandler(tools = List(textTool))
      out <- call(h, "tools/call", Some(CallToolRequest("nope", Json.obj()).asJson))
    yield out match
      case Some(Message.Response(_, _, None, Some(err))) =>
        assertEquals(err.code, ErrorCode.InternalError)
        assert(err.message.contains("nope"), err.message)
      case other => fail(s"unexpected $other")
  }

  test("resources/list returns configured resources") {
    for
      h   <- buildHandler(resources = List(staticResource))
      out <- call(h, "resources/list")
    yield out match
      case Some(Message.Response(_, _, Some(r), None)) =>
        val uris = r.hcursor.downField("resources").as[List[ResourceDefinition]].toOption.map(_.map(_.uri))
        assertEquals(uris, Some(List("x://r")))
      case other => fail(s"unexpected $other")
  }

  test("resources/read returns the resource content") {
    for
      h   <- buildHandler(resources = List(staticResource))
      out <- call(h, "resources/read", Some(ReadResourceRequest("x://r").asJson))
    yield out match
      case Some(Message.Response(_, _, Some(r), None)) =>
        val text = r.hcursor.downField("contents").downN(0).get[String]("text").toOption
        assertEquals(text, Some("hi"))
      case other => fail(s"unexpected $other")
  }

  test("resources/templates/list returns an empty list when no templates") {
    for
      h   <- buildHandler()
      out <- call(h, "resources/templates/list")
    yield out match
      case Some(Message.Response(_, _, Some(r), None)) =>
        val templates = r.hcursor.downField("resourceTemplates").as[List[ResourceTemplateDefinition]].toOption
        assertEquals(templates, Some(Nil))
      case other => fail(s"unexpected $other")
  }

  test("resources/subscribe and unsubscribe return empty objects") {
    for
      h     <- buildHandler()
      sub   <- call(h, "resources/subscribe", Some(SubscribeRequest("x://r").asJson))
      unsub <- call(h, "resources/unsubscribe", Some(UnsubscribeRequest("x://r").asJson))
    yield
      assertEquals(sub.collect { case Message.Response(_, _, Some(r), None) => r }, Some(Json.obj()))
      assertEquals(unsub.collect { case Message.Response(_, _, Some(r), None) => r }, Some(Json.obj()))
  }

  test("prompts/list returns configured prompts") {
    for
      h   <- buildHandler(prompts = List(staticPrompt))
      out <- call(h, "prompts/list")
    yield out match
      case Some(Message.Response(_, _, Some(r), None)) =>
        val names = r.hcursor.downField("prompts").as[List[PromptDefinition]].toOption.map(_.map(_.name))
        assertEquals(names, Some(List("p")))
      case other => fail(s"unexpected $other")
  }

  test("prompts/get returns the prompt messages") {
    for
      h   <- buildHandler(prompts = List(staticPrompt))
      out <- call(h, "prompts/get", Some(GetPromptRequest("p", Map.empty).asJson))
    yield out match
      case Some(Message.Response(_, _, Some(r), None)) =>
        val messages = r.hcursor.downField("messages").as[List[PromptMessage]].toOption
        assertEquals(messages.map(_.size), Some(1))
      case other => fail(s"unexpected $other")
  }

  test("unknown method returns an internalError-shaped response (current behavior, spec-divergent)") {
    for
      h   <- buildHandler()
      out <- call(h, "tools/explode")
    yield out match
      case Some(Message.Response(_, _, None, Some(err))) =>
        assertEquals(err.code, ErrorCode.InternalError)
      case other => fail(s"unexpected $other")
  }

  test("notifications/initialized returns None (no response)") {
    for
      h   <- buildHandler()
      out <- h.handle(Message.notification("notifications/initialized", None))
    yield assertEquals(out, None)
  }

  test("unknown notification method returns None (silent ignore)") {
    for
      h   <- buildHandler()
      out <- h.handle(Message.notification("notifications/bogus", None))
    yield assertEquals(out, None)
  }

  test("Message.Response from client routes to ServerRequester.completeResponse and emits no outbound message") {
    for
      published <- Queue.unbounded[IO, Message]
      requester <- ServerRequester.create[IO](published.offer)
      cancel    <- CancellationRegistry.create[IO]
      server    <- DefaultServer[IO](Implementation("t", "0"), ServerCapabilities())
      handler    = new RequestHandler[IO](server, requester, cancel)
      reqFib    <- requester.request("ping", None).start
      msg       <- published.take
      reqId      = msg match
                case r: Message.Request => r.id
                case other              => fail(s"expected Request, got $other")
      out    <- handler.handle(Message.response(reqId, Json.obj("ok" -> true.asJson)))
      result <- reqFib.joinWithNever
    yield
      assertEquals(out, None)
      assertEquals(result, Right(Json.obj("ok" -> true.asJson)))
  }
