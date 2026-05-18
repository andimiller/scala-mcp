package net.andimiller.mcp.core.server

import cats.effect.IO

import net.andimiller.mcp.core.protocol.*

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

class MetadataSuite extends FunSuite:

  test("Metadata.empty has no fields set") {
    val m = Metadata.empty
    assertEquals(m.title, None)
    assertEquals(m.icons, Nil)
    assertEquals(m.meta, None)
  }

  test("fluent setters compose") {
    val icon = Icon.png("https://x/a.png")
    val m    = Metadata.empty.title("T").icon(icon).meta("k", "v".asJson)
    assertEquals(m.title, Some("T"))
    assertEquals(m.icons, List(icon))
    assertEquals(m.meta, Some(JsonObject("k" -> "v".asJson)))
  }

  test("icon(...) appends; icons(...) replaces") {
    val a = Icon.png("https://x/a.png")
    val b = Icon.png("https://x/b.png")
    assertEquals(Metadata.empty.icon(a).icon(b).icons, List(a, b))
    assertEquals(Metadata.empty.icon(a).icon(b).icons(List(a)).icons, List(a))
  }

  test("meta(key, value) accumulates keys") {
    val m = Metadata.empty.meta("k1", 1.asJson).meta("k2", 2.asJson)
    assertEquals(m.meta, Some(JsonObject("k1" -> 1.asJson, "k2" -> 2.asJson)))
  }

  test("meta(JsonObject) replaces the full object") {
    val m = Metadata.empty.meta("old", 1.asJson).meta(JsonObject("new" -> 2.asJson))
    assertEquals(m.meta, Some(JsonObject("new" -> 2.asJson)))
  }

  test(".metadata(m) overwrites builder title/icons/meta; subsequent flat calls override again") {
    val brand   = Metadata.empty.title("Brand").icon(Icon.png("https://x/brand.png"))
    val initial = Tool.builder.name("t").title("Old").icon(Icon.png("https://x/old.png"))
    val applied = initial.metadata(brand).title("Newest")
    // After metadata(brand): title=Brand, icons=[brand]. After title("Newest"): title=Newest, icons still brand's.
    val resolved = applied.runResult[IO](_ => IO.pure(ToolResult.Text("x")))
    assertEquals(resolved.title, Some("Newest"))
    assertEquals(resolved.icons.map(_.src), List("https://x/brand.png"))
  }
