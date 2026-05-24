package net.andimiller.mcp.apps.tyrian

import io.circe.Json
import io.circe.parser
import io.circe.syntax.*

import munit.FunSuite

class BridgeProtocolSuite extends FunSuite:

  test("DisplayMode encodes to the spec strings") {
    assertEquals(DisplayMode.Inline.asJson.noSpaces, "\"inline\"")
    assertEquals(DisplayMode.Fullscreen.asJson.noSpaces, "\"fullscreen\"")
    assertEquals(DisplayMode.Pip.asJson.noSpaces, "\"pip\"")
  }

  test("DisplayMode decodes from the spec strings") {
    assertEquals(parser.decode[DisplayMode]("\"pip\""), Right(DisplayMode.Pip))
    assert(parser.decode[DisplayMode]("\"nope\"").isLeft)
  }

  test("JsonRpc.request envelope has jsonrpc, id, method, params") {
    val req = JsonRpc.request(7L, "ui/open-link", Json.obj("url" -> "https://x".asJson))
    val obj = req.asObject.get
    assertEquals(obj("jsonrpc").map(_.noSpaces), Some("\"2.0\""))
    assertEquals(obj("id").map(_.noSpaces), Some("7"))
    assertEquals(obj("method").map(_.noSpaces), Some("\"ui/open-link\""))
    assertEquals(obj("params").get.hcursor.downField("url").as[String], Right("https://x"))
  }

  test("JsonRpc.notification has no id field") {
    val n = JsonRpc.notification("ui/notifications/initialized", Json.obj())
    assertEquals(n.asObject.get.contains("id"), false)
    assertEquals(n.asObject.get("method").map(_.noSpaces), Some("\"ui/notifications/initialized\""))
  }

  test("JsonRpc.response has result, no error") {
    val r = JsonRpc.response(Json.fromLong(1), Json.obj("ok" -> true.asJson))
    val o = r.asObject.get
    assert(o.contains("result"))
    assert(!o.contains("error"))
  }

  test("BridgeMethods constants match the spec wire strings") {
    assertEquals(BridgeMethods.UiInitialize, "ui/initialize")
    assertEquals(BridgeMethods.UiNotificationsInitialized, "ui/notifications/initialized")
    assertEquals(BridgeMethods.UiNotificationsToolResult, "ui/notifications/tool-result")
    assertEquals(BridgeMethods.UiOpenLink, "ui/open-link")
    assertEquals(BridgeMethods.UiRequestDisplayMode, "ui/request-display-mode")
    assertEquals(BridgeMethods.ToolsCall, "tools/call")
    assertEquals(BridgeMethods.ResourcesRead, "resources/read")
  }

  test("tools/call envelope nests {name, arguments}") {
    val req = JsonRpc.request(3L, BridgeMethods.ToolsCall, Json.obj("name" -> "get_time".asJson, "arguments" -> Json.obj()))
    val o   = req.asObject.get
    assertEquals(o("method").get, "tools/call".asJson)
    assertEquals(o("params").get.hcursor.downField("name").as[String], Right("get_time"))
  }
