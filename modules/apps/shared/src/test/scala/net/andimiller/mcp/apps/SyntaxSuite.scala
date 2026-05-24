package net.andimiller.mcp.apps

import cats.effect.IO

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

import net.andimiller.mcp.apps.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.McpResource
import net.andimiller.mcp.core.server.Tool

class SyntaxSuite extends FunSuite:

  test("tool.asApp adds _meta.ui without clobbering user-set _meta keys") {
    val resolved = Tool.builder
      .name("clock")
      .meta("com.example/brand", "acme".asJson)
      .asApp("ui://clock")
      .runResult[IO](_ => IO.pure(ToolResult.Text("ok")))

    val meta = resolved.meta.getOrElse(JsonObject.empty)
    assertEquals(meta("com.example/brand"), Some("acme".asJson))
    assertEquals(
      meta(AppsExtension.MetaKey).map(_.deepDropNullValues),
      Some(ToolUiMetadata("ui://clock").asJson.deepDropNullValues)
    )
  }

  test("resource.appUi + appHtml set _meta and mime") {
    val resource = McpResource.builder
      .uri("ui://clock")
      .name("clock")
      .appHtml
      .appUi(ResourceUiMetadata(prefersBorder = Some(true)))
      .staticContent[IO]("<html/>")

    assertEquals(resource.mimeType, Some(AppsExtension.MimeType))
    val meta = resource.meta.getOrElse(JsonObject.empty)
    assertEquals(
      meta(AppsExtension.MetaKey).map(_.deepDropNullValues),
      Some(ResourceUiMetadata(prefersBorder = Some(true)).asJson.deepDropNullValues)
    )
  }

  test("AppResource.html requires ui:// prefix") {
    intercept[IllegalArgumentException] {
      AppResource.html[IO](uri = "http://example.com/x", name = "bad", html = "<html/>")
    }
  }

  test("AppResource.html sets mime, _meta.ui, and html content") {
    val r    = AppResource.html[IO](
      uri = "ui://clock",
      name = "clock",
      html = "<html><body>hi</body></html>",
      ui = ResourceUiMetadata(permissions = Some(List(AppPermission.ClipboardWrite)))
    )
    assertEquals(r.uri, "ui://clock")
    assertEquals(r.mimeType, Some(AppsExtension.MimeType))
    val meta = r.meta.getOrElse(JsonObject.empty)
    assert(meta(AppsExtension.MetaKey).isDefined)
  }
