package net.andimiller.mcp.core.codecs

import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import munit.FunSuite
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.PromptRole
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

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
