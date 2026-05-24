package net.andimiller.mcp.apps.tyrian

import io.circe.Json

import munit.FunSuite

class BridgeParserSuite extends FunSuite:

  private val emptyPending = BridgeParser.PendingRequests.fromSet(Set.empty)

  test("parses ui/notifications/tool-result into ToolResult") {
    val raw = """{"jsonrpc":"2.0","method":"ui/notifications/tool-result","params":{"foo":42}}"""
    val ev  = BridgeParser.parse(raw, emptyPending)
    ev match
      case HostEvent.ToolResult(p) =>
        assertEquals(p.hcursor.downField("foo").as[Int], Right(42))
      case other                   => fail(s"expected ToolResult, got $other")
  }

  test("parses ui/notifications/host-context-changed") {
    val raw = """{"jsonrpc":"2.0","method":"ui/notifications/host-context-changed","params":{"theme":"dark"}}"""
    val ev  = BridgeParser.parse(raw, emptyPending)
    assert(ev.isInstanceOf[HostEvent.HostContextChanged])
  }

  test("parses ui/resource-teardown as a request") {
    val raw = """{"jsonrpc":"2.0","id":42,"method":"ui/resource-teardown"}"""
    val ev  = BridgeParser.parse(raw, emptyPending)
    ev match
      case HostEvent.ResourceTeardown(id) => assertEquals(id, Json.fromInt(42))
      case other                           => fail(s"expected ResourceTeardown, got $other")
  }

  test("parses ping request") {
    val raw = """{"jsonrpc":"2.0","id":"abc","method":"ping"}"""
    val ev  = BridgeParser.parse(raw, emptyPending)
    ev match
      case HostEvent.Ping(id) => assertEquals(id, Json.fromString("abc"))
      case other              => fail(s"expected Ping, got $other")
  }

  test("synthesises Ready from a ui/initialize response when id matches pending") {
    val pending = BridgeParser.PendingRequests.fromSet(Set(Json.fromInt(1)))
    val raw     = """{"jsonrpc":"2.0","id":1,"result":{"hostContext":{"theme":"light"}}}"""
    val ev      = BridgeParser.parse(raw, pending)
    ev match
      case HostEvent.Ready(ctx) =>
        assertEquals(ctx.hcursor.downField("theme").as[String], Right("light"))
      case other                => fail(s"expected Ready, got $other")
  }

  test("unmatched response id becomes Malformed") {
    val raw = """{"jsonrpc":"2.0","id":99,"result":{}}"""
    val ev  = BridgeParser.parse(raw, emptyPending)
    assert(ev.isInstanceOf[HostEvent.Malformed], s"got $ev")
  }

  test("response to a tools/call request surfaces as ToolResult") {
    val pending = BridgeParser.PendingRequests.of(Set.empty, Set(Json.fromInt(7)))
    val raw     = """{"jsonrpc":"2.0","id":7,"result":{"structuredContent":{"isoTime":"2026-01-01T00:00:00Z"}}}"""
    val ev      = BridgeParser.parse(raw, pending)
    ev match
      case HostEvent.ToolResult(p) =>
        assertEquals(p.hcursor.downField("structuredContent").downField("isoTime").as[String], Right("2026-01-01T00:00:00Z"))
      case other                   => fail(s"expected ToolResult, got $other")
  }

  test("unknown notification becomes Malformed") {
    val raw = """{"jsonrpc":"2.0","method":"ui/notifications/bogus","params":{}}"""
    val ev  = BridgeParser.parse(raw, emptyPending)
    assert(ev.isInstanceOf[HostEvent.Malformed], s"got $ev")
  }

  test("non-JSON input becomes Malformed") {
    val ev = BridgeParser.parse("not json", emptyPending)
    assert(ev.isInstanceOf[HostEvent.Malformed], s"got $ev")
  }

  test("fromJson on a notification with no params still produces an event with empty object") {
    val raw  = """{"jsonrpc":"2.0","method":"ui/notifications/tool-input"}"""
    val ev   = BridgeParser.parse(raw, emptyPending)
    ev match
      case HostEvent.ToolInput(p) => assertEquals(p, Json.obj())
      case other                  => fail(s"expected ToolInput, got $other")
  }
