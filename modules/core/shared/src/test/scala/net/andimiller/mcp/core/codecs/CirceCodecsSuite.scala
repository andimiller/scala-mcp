package net.andimiller.mcp.core.codecs

import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

class CirceCodecsSuite extends FunSuite:

  private def roundTrip[A: Encoder: Decoder](value: A): A =
    value.asJson.as[A].fold(err => fail(s"decode failed: $err"), identity)

  test("RequestId String round-trips and stays a JSON string") {
    val id   = RequestId.fromString("abc-1")
    val json = id.asJson
    assert(json.isString, s"expected string JSON, got $json")
    assertEquals(roundTrip(id).asString, Some("abc-1"))
  }

  test("RequestId Long round-trips and stays a JSON number") {
    val id   = RequestId.fromLong(42L)
    val json = id.asJson
    assert(json.isNumber, s"expected number JSON, got $json")
    assertEquals(roundTrip(id).asLong, Some(42L))
  }

  test("Message.Request encodes id+method+params, no result/error") {
    val msg = Message.request(RequestId.fromLong(1L), "ping", Some(Json.obj("k" -> "v".asJson)))
    val obj = msg.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("jsonrpc"), Some("2.0".asJson))
    assertEquals(obj("id"), Some(1L.asJson))
    assertEquals(obj("method"), Some("ping".asJson))
    assertEquals(obj("params"), Some(Json.obj("k" -> "v".asJson)))
    assertEquals(obj("result"), None)
    assertEquals(obj("error"), None)
  }

  test("Message.Request decodes back to a Request") {
    val msg = Message.request(RequestId.fromString("r1"), "tools/list", None)
    roundTrip(msg) match
      case Message.Request(_, id, method, params) =>
        assertEquals(id.asString, Some("r1"))
        assertEquals(method, "tools/list")
        assertEquals(params, None)
      case other => fail(s"expected Request, got $other")
  }

  test("Message.Response success encodes result and OMITS error field") {
    val msg = Message.response(RequestId.fromLong(7L), Json.obj("ok" -> true.asJson))
    val obj = msg.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("result"), Some(Json.obj("ok" -> true.asJson)))
    assertEquals(obj("error"), None, "error must not appear on success responses (deepDropNullValues)")
    assertEquals(obj("method"), None)
  }

  test("Message.Response error encodes error and OMITS result field") {
    val err = JsonRpcError(-32000, "boom", None)
    val msg = Message.errorResponse(RequestId.fromLong(7L), err)
    val obj = msg.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("result"), None, "result must not appear on error responses")
    assert(obj("error").isDefined)
  }

  test("Message.Response decodes back to a Response") {
    val msg = Message.response(RequestId.fromLong(7L), Json.obj("v" -> 1.asJson))
    roundTrip(msg) match
      case Message.Response(_, id, result, error) =>
        assertEquals(id.asLong, Some(7L))
        assertEquals(result, Some(Json.obj("v" -> 1.asJson)))
        assertEquals(error, None)
      case other => fail(s"expected Response, got $other")
  }

  test("Message.Notification encodes without an id field") {
    val msg = Message.notification("notifications/initialized", None)
    val obj = msg.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("id"), None)
    assertEquals(obj("method"), Some("notifications/initialized".asJson))
  }

  test("Message.Notification decodes back to a Notification") {
    val msg = Message.notification("notifications/initialized", None)
    roundTrip(msg) match
      case Message.Notification(_, method, _) => assertEquals(method, "notifications/initialized")
      case other                              => fail(s"expected Notification, got $other")
  }

  test("decoder picks Request when id+method are present") {
    val js = parse("""{"jsonrpc":"2.0","id":1,"method":"ping","params":null}""").toOption.get
    js.as[Message] match
      case Right(_: Message.Request) => ()
      case other                     => fail(s"expected Request, got $other")
  }

  test("decoder picks Response when id+result are present") {
    val js = parse("""{"jsonrpc":"2.0","id":1,"result":{}}""").toOption.get
    js.as[Message] match
      case Right(_: Message.Response) => ()
      case other                      => fail(s"expected Response, got $other")
  }

  test("decoder picks Notification when method is present without id") {
    val js = parse("""{"jsonrpc":"2.0","method":"foo"}""").toOption.get
    js.as[Message] match
      case Right(_: Message.Notification) => ()
      case other                          => fail(s"expected Notification, got $other")
  }

  test("JsonRpcError round-trips with and without data") {
    assertEquals(roundTrip(JsonRpcError(-32000, "x", None)), JsonRpcError(-32000, "x", None))
    val withData = JsonRpcError(-32000, "x", Some(Json.obj("k" -> "v".asJson)))
    assertEquals(roundTrip(withData), withData)
  }

  test("Content.Text round-trips with type discriminator") {
    val c   = Content.Text("hi")
    val obj = c.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("type"), Some("text".asJson))
    assertEquals(roundTrip(c), c)
  }

  test("Content.Image round-trips") {
    val c = Content.Image("AAAA", "image/png")
    assertEquals(roundTrip(c), c)
  }

  test("Content.Audio round-trips") {
    val c = Content.Audio("BBBB", "audio/wav")
    assertEquals(roundTrip(c), c)
  }

  test("Content.Resource round-trips with optional fields") {
    val full = Content.Resource("file:///a", Some("text/plain"), Some("hi"))
    assertEquals(roundTrip(full), full)
    val bare = Content.Resource("file:///a", None, None)
    assertEquals(roundTrip(bare), bare)
  }

  test("Content decoder rejects unknown type") {
    val js = parse("""{"type":"video","data":"x"}""").toOption.get
    assert(js.as[Content].isLeft)
  }

  test("PromptRole encodes as bare string") {
    assertEquals(PromptRole.User.asJson, "user".asJson)
    assertEquals(PromptRole.Assistant.asJson, "assistant".asJson)
  }

  test("PromptRole round-trips") {
    assertEquals(roundTrip(PromptRole.User), PromptRole.User)
    assertEquals(roundTrip(PromptRole.Assistant), PromptRole.Assistant)
  }

  test("PromptRole decoder rejects unknown role") {
    val js = "robot".asJson
    assert(js.as[PromptRole].isLeft)
  }

  // ─── 2025-11-25 metadata sweep ─────────────────────────────────────

  test("Icon round-trips with all fields") {
    val full = Icon("https://x/y.png", Some("image/png"), Some(List("48x48", "any")), Some(IconTheme.Dark))
    assertEquals(roundTrip(full), full)
  }

  test("Icon.dataEncode produces a base64 data URI for the given mimeType") {
    val svg  = "<svg/>"
    val icon = Icon.dataEncode("image/svg+xml", svg)
    assertEquals(icon.mimeType, Some("image/svg+xml"))
    assertEquals(icon.src, "data:image/svg+xml;base64,PHN2Zy8+")
  }

  test("IconTheme encodes as bare lowercase string") {
    assertEquals(IconTheme.Light.asJson, "light".asJson)
    assertEquals(IconTheme.Dark.asJson, "dark".asJson)
    assert("midnight".asJson.as[IconTheme].isLeft)
  }

  test("Role encodes as bare lowercase string") {
    assertEquals(Role.User.asJson, "user".asJson)
    assertEquals(Role.Assistant.asJson, "assistant".asJson)
  }

  test("Annotations round-trips") {
    val a =
      Annotations(audience = Some(List(Role.User)), priority = Some(0.9), lastModified = Some("2026-01-01T00:00:00Z"))
    assertEquals(roundTrip(a), a)
  }

  test("ToolAnnotations round-trips with all hints") {
    val a = ToolAnnotations(Some("Display"), Some(true), Some(false), Some(true), Some(false))
    assertEquals(roundTrip(a), a)
  }

  test("ToolAnnotations presets encode the expected booleans") {
    assertEquals(ToolAnnotations.read.readOnlyHint, Some(true))
    assertEquals(ToolAnnotations.read.destructiveHint, Some(false))
    assertEquals(ToolAnnotations.idempotent.idempotentHint, Some(true))
    assertEquals(ToolAnnotations.closedWorld.openWorldHint, Some(false))
  }

  test("TaskSupport round-trips all three values") {
    assertEquals(TaskSupport.Forbidden.asJson, "forbidden".asJson)
    assertEquals(TaskSupport.Optional.asJson, "optional".asJson)
    assertEquals(TaskSupport.Required.asJson, "required".asJson)
    assertEquals(roundTrip(TaskSupport.Optional), TaskSupport.Optional)
    assert("never".asJson.as[TaskSupport].isLeft)
  }

  test("ToolExecution round-trips") {
    val e = ToolExecution(TaskSupport.Optional)
    assertEquals(roundTrip(e), e)
  }

  test("ToolDefinition emits _meta wire key, not 'meta'") {
    val td = ToolDefinition(
      "n",
      Some("d"),
      JsonObject.empty.asJson,
      None,
      _meta = Some(JsonObject("k" -> "v".asJson))
    )
    val obj = td.asJson.asObject.getOrElse(fail("expected object"))
    assert(obj("_meta").isDefined, "expected wire key _meta")
    assertEquals(obj("meta"), None, "Scala field name 'meta' must NOT leak onto the wire")
  }

  test("ToolDefinition with no metadata decodes from minimal JSON (back-compat)") {
    val js = parse("""{"name":"x","description":"y","inputSchema":{}}""").toOption.get
    val td = js.as[ToolDefinition].fold(err => fail(s"decode: $err"), identity)
    assertEquals(td.name, "x")
    assertEquals(td.title, None)
    assertEquals(td.icons, None)
    assertEquals(td.annotations, None)
    assertEquals(td.execution, None)
    assertEquals(td._meta, None)
  }

  test("ToolDefinition round-trips with full metadata payload") {
    val td = ToolDefinition(
      name = "delete",
      description = Some("destructive"),
      inputSchema = JsonObject.empty.asJson,
      outputSchema = None,
      title = Some("Delete"),
      icons = Some(List(Icon.png("https://x/icon.png"))),
      annotations = Some(ToolAnnotations.destroy),
      execution = Some(ToolExecution.taskOptional),
      _meta = Some(JsonObject("io.modelcontextprotocol/audit" -> "high".asJson))
    )
    assertEquals(roundTrip(td), td)
  }

  test("ResourceDefinition.size round-trips as a Long") {
    val r   = ResourceDefinition("file:///a", "a", size = Some(12345L))
    val obj = r.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("size"), Some(12345L.asJson))
    assertEquals(roundTrip(r), r)
  }

  test("PromptArgument carries title when set") {
    val a = PromptArgument("x", Some("desc"), required = true, title = Some("X label"))
    assertEquals(roundTrip(a), a)
  }

  test("Implementation new fields round-trip") {
    val i = Implementation(
      "scala-mcp", "0.1.0", title = Some("Scala MCP"), description = Some("A library"),
      icons = Some(List(Icon.svg("https://x/logo.svg"))), websiteUrl = Some("https://example.com")
    )
    assertEquals(roundTrip(i), i)
  }

  test("Root carries _meta when set") {
    val r = Root("file:///x", Some("x"), Some(JsonObject("ns.example/k" -> 1.asJson)))
    assertEquals(roundTrip(r), r)
  }

  test("Content.Text round-trips with annotations + _meta") {
    val c: Content = Content.Text(
      "hi",
      annotations = Some(Annotations(priority = Some(0.5))),
      _meta = Some(JsonObject("io.modelcontextprotocol/tag" -> "alpha".asJson))
    )
    assertEquals(roundTrip(c), c)
  }

  test("Content encoder strips nulls for absent optional fields") {
    val c: Content = Content.Text("hi")
    val obj        = c.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("type"), Some("text".asJson))
    assertEquals(obj("text"), Some("hi".asJson))
    assertEquals(obj("annotations"), None)
    assertEquals(obj("_meta"), None)
  }

  test("Content.Resource encoder strips nulls for absent mimeType/text") {
    val c: Content = Content.Resource("file:///a")
    val obj        = c.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("uri"), Some("file:///a".asJson))
    assertEquals(obj("mimeType"), None)
    assertEquals(obj("text"), None)
    assertEquals(obj("annotations"), None)
    assertEquals(obj("_meta"), None)
  }

  test("Content.ResourceLink round-trips with full payload") {
    val c: Content = Content.ResourceLink(
      uri = "https://x/r",
      name = "rname",
      title = Some("R Title"),
      description = Some("desc"),
      mimeType = Some("text/plain"),
      size = Some(1024L),
      icons = Some(List(Icon.png("https://x/i.png"))),
      annotations = Some(Annotations(priority = Some(1.0))),
      _meta = Some(JsonObject("k" -> "v".asJson))
    )
    val obj = c.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("type"), Some("resource_link".asJson))
    assertEquals(roundTrip(c), c)
  }

  test("Content decoder routes resource_link to ResourceLink") {
    val js = parse("""{"type":"resource_link","uri":"file:///a","name":"a"}""").toOption.get
    js.as[Content] match
      case Right(_: Content.ResourceLink) => ()
      case other                          => fail(s"expected ResourceLink, got $other")
  }

  // ─── 2025-11-25 spec gap fix-ups ────────────────────────────────────

  test("Content.Resource embedded variant round-trips with blob set") {
    val c: Content = Content.Resource("file:///a", Some("application/octet-stream"), text = None, blob = Some("AAAA"))
    assertEquals(roundTrip(c), c)
    val obj = c.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("blob"), Some("AAAA".asJson))
    assertEquals(obj("text"), None) // null stripped by deepDropNullValues
  }

  test("ToolDefinition decodes when description is absent") {
    val js = parse("""{"name":"x","inputSchema":{}}""").toOption.get
    val td = js.as[ToolDefinition].fold(err => fail(s"decode: $err"), identity)
    assertEquals(td.name, "x")
    assertEquals(td.description, None)
  }

  test("ResourceContent codec does not emit 'annotations' (field is gone)") {
    val rc  = ResourceContent.text("file:///a", "hi", Some("text/plain"))
    val obj = rc.asJson.asObject.getOrElse(fail("expected object"))
    assertEquals(obj("annotations"), None, "annotations was removed; codec must not emit it")
  }

  test("CallToolResponse carries _meta on the wire") {
    val resp = CallToolResponse(
      content = List(Content.Text("ok")),
      _meta = Some(JsonObject("io.modelcontextprotocol/audit" -> "high".asJson))
    )
    assertEquals(roundTrip(resp), resp)
    val obj = resp.asJson.asObject.getOrElse(fail("expected object"))
    assert(obj("_meta").isDefined, "expected wire key _meta")
  }

  test("InitializeResponse.instructions round-trips") {
    val r = InitializeResponse(
      protocolVersion = "2025-11-25",
      capabilities = ServerCapabilities(),
      serverInfo = Implementation("test", "0.1.0"),
      instructions = Some("Be concise.")
    )
    assertEquals(roundTrip(r), r)
  }

  test("ServerCapabilities exercises completions + experimental") {
    val caps = ServerCapabilities(
      completions = Some(CompletionsCapabilities()),
      experimental = Some(JsonObject("com.example/feature" -> true.asJson))
    )
    assertEquals(roundTrip(caps), caps)
  }

  test("ToolListChangedNotification + PromptListChangedNotification round-trip") {
    assertEquals(roundTrip(ToolListChangedNotification()), ToolListChangedNotification())
    assertEquals(roundTrip(PromptListChangedNotification()), PromptListChangedNotification())
  }
