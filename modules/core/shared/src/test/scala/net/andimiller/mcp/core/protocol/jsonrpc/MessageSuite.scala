package net.andimiller.mcp.core.protocol.jsonrpc

import io.circe.Json
import munit.FunSuite

class MessageSuite extends FunSuite:

  test("RequestId.fromString preserves the underlying string") {
    val id = RequestId.fromString("abc")
    assertEquals(id.asString, Some("abc"))
    assertEquals(id.asLong, None)
  }

  test("RequestId.fromLong preserves the underlying long") {
    val id = RequestId.fromLong(42L)
    assertEquals(id.asLong, Some(42L))
    assertEquals(id.asString, None)
  }

  test("RequestId.value exposes the union") {
    assertEquals(RequestId.fromString("x").value, "x")
    assertEquals(RequestId.fromLong(1L).value, 1L)
  }

  test("JsonRpcError factories produce the documented codes") {
    assertEquals(JsonRpcError.parseError().code, ErrorCode.ParseError)
    assertEquals(JsonRpcError.invalidRequest().code, ErrorCode.InvalidRequest)
    assertEquals(JsonRpcError.methodNotFound("foo").code, ErrorCode.MethodNotFound)
    assertEquals(JsonRpcError.invalidParams().code, ErrorCode.InvalidParams)
    assertEquals(JsonRpcError.internalError().code, ErrorCode.InternalError)
  }

  test("methodNotFound includes the method name in message") {
    assert(JsonRpcError.methodNotFound("nope").message.contains("nope"))
  }

  test("Message.request sets jsonrpc=2.0 and the supplied fields") {
    val m = Message.request(RequestId.fromLong(1L), "ping", Some(Json.obj()))
    m match
      case Message.Request(jsonrpc, id, method, params) =>
        assertEquals(jsonrpc, "2.0")
        assertEquals(id.asLong, Some(1L))
        assertEquals(method, "ping")
        assertEquals(params, Some(Json.obj()))
      case other => fail(s"expected Request, got $other")
  }

  test("Message.response sets result and no error") {
    val m = Message.response(RequestId.fromLong(1L), Json.True)
    m match
      case Message.Response(jsonrpc, _, result, error) =>
        assertEquals(jsonrpc, "2.0")
        assertEquals(result, Some(Json.True))
        assertEquals(error, None)
      case other => fail(s"expected Response, got $other")
  }

  test("Message.errorResponse sets error and no result") {
    val err = JsonRpcError(-32000, "x", None)
    val m   = Message.errorResponse(RequestId.fromLong(1L), err)
    m match
      case Message.Response(_, _, result, error) =>
        assertEquals(result, None)
        assertEquals(error, Some(err))
      case other => fail(s"expected Response, got $other")
  }

  test("Message.notification sets jsonrpc=2.0 and has no id") {
    val m = Message.notification("foo", None)
    m match
      case Message.Notification(jsonrpc, method, params) =>
        assertEquals(jsonrpc, "2.0")
        assertEquals(method, "foo")
        assertEquals(params, None)
      case other => fail(s"expected Notification, got $other")
  }
