package net.andimiller.mcp.core.server

import cats.Applicative
import cats.syntax.all.*

import munit.FunSuite

class UriPathSuite extends FunSuite:

  // ── static ────────────────────────────────────────────────────────

  test("static matches literal prefix") {
    val p = UriPath.static("foo://")
    assertEquals(p.parse("foo://bar"), Some(((), "bar")))
  }

  test("static rejects non-matching input") {
    val p = UriPath.static("foo://")
    assertEquals(p.parse("bar://x"), None)
  }

  test("static template is the prefix itself") {
    assertEquals(UriPath.static("foo://").template, "foo://")
  }

  test("static matches exact input with empty remainder") {
    val p = UriPath.static("exact")
    assertEquals(p.parse("exact"), Some(((), "")))
  }

  // ── named ─────────────────────────────────────────────────────────

  test("named matches a single segment") {
    val p = UriPath.named("id")
    assertEquals(p.parse("hello/rest"), Some(("hello", "/rest")))
  }

  test("named matches to end of string when no slash") {
    val p = UriPath.named("id")
    assertEquals(p.parse("hello"), Some(("hello", "")))
  }

  test("named rejects empty segment") {
    val p = UriPath.named("id")
    assertEquals(p.parse(""), None)
  }

  test("named rejects input starting with slash") {
    val p = UriPath.named("id")
    assertEquals(p.parse("/foo"), None)
  }

  test("named template is {name}") {
    assertEquals(UriPath.named("user_id").template, "{user_id}")
  }

  // ── rest ──────────────────────────────────────────────────────────

  test("rest matches all remaining input") {
    val p = UriPath.rest
    assertEquals(p.parse("foo/bar/baz"), Some(("foo/bar/baz", "")))
  }

  test("rest matches empty input") {
    val p = UriPath.rest
    assertEquals(p.parse(""), Some(("", "")))
  }

  test("rest template is empty") {
    assertEquals(UriPath.rest.template, "")
  }

  // ── Applicative composition (*>) ──────────────────────────────────

  test("static *> named extracts segment after prefix") {
    val p = UriPath.static("pomodoro://timers/") *> UriPath.named("name")
    assertEquals(p.parse("pomodoro://timers/work"), Some(("work", "")))
  }

  test("static *> named rejects wrong prefix") {
    val p = UriPath.static("pomodoro://timers/") *> UriPath.named("name")
    assertEquals(p.parse("other://timers/work"), None)
  }

  test("static *> named template is concatenated") {
    val p = UriPath.static("pomodoro://timers/") *> UriPath.named("name")
    assertEquals(p.template, "pomodoro://timers/{name}")
  }

  test("composed path rejects trailing content when checked with collect") {
    val p = UriPath.static("app://") *> UriPath.named("id")
    // With trailing content, parse succeeds but remainder is non-empty
    val result = p.parse("app://123/extra")
    assertEquals(result, Some(("123", "/extra")))
    // Full-consumption check (as used in builders):
    assertEquals(result.collect { case (a, "") => a }, None)
  }

  // ── tupled ────────────────────────────────────────────────────────

  test("tupled extracts two segments") {
    val p = UriPath.static("notebook://") *>
      (UriPath.named("username"), UriPath.static("/") *> UriPath.named("note_id")).tupled
    assertEquals(p.parse("notebook://alice/note1"), Some((("alice", "note1"), "")))
  }

  test("tupled template is correct") {
    val p = UriPath.static("notebook://") *>
      (UriPath.named("username"), UriPath.static("/") *> UriPath.named("note_id")).tupled
    assertEquals(p.template, "notebook://{username}/{note_id}")
  }

  test("tupled rejects missing second segment") {
    val p = UriPath.static("a://") *>
      (UriPath.named("x"), UriPath.static("/") *> UriPath.named("y")).tupled
    // "a://foo" has no slash+second segment
    assertEquals(p.parse("a://foo"), None)
  }

  // ── map ───────────────────────────────────────────────────────────

  test("map transforms extracted value") {
    val p = UriPath.named("n").map(_.toUpperCase)
    assertEquals(p.parse("hello"), Some(("HELLO", "")))
  }

  test("map preserves template") {
    val p = UriPath.named("n").map(_.length)
    assertEquals(p.template, "{n}")
  }

  // ── mapOption ─────────────────────────────────────────────────────

  test("mapOption filters with None") {
    val p = UriPath.named("n").mapOption(s => Option.when(s.length > 3)(s))
    assertEquals(p.parse("hi"), None)
    assertEquals(p.parse("hello"), Some(("hello", "")))
  }

  // ── PathParam / .as ───────────────────────────────────────────────

  test("as[Int] converts valid integer segment") {
    val p = UriPath.static("item/") *> UriPath.named("id").as[Int]
    assertEquals(p.parse("item/42"), Some((42, "")))
  }

  test("as[Int] rejects non-integer segment") {
    val p = UriPath.static("item/") *> UriPath.named("id").as[Int]
    assertEquals(p.parse("item/abc"), None)
  }

  test("as[Long] converts valid long segment") {
    val p = UriPath.named("big").as[Long]
    assertEquals(p.parse("9999999999"), Some((9999999999L, "")))
  }

  test("as[Long] rejects non-numeric segment") {
    val p = UriPath.named("big").as[Long]
    assertEquals(p.parse("nope"), None)
  }

  test("as[String] is identity") {
    val p = UriPath.named("s").as[String]
    assertEquals(p.parse("hello"), Some(("hello", "")))
  }

  test("as preserves template") {
    val p = UriPath.named("id").as[Int]
    assertEquals(p.template, "{id}")
  }

  // ── pure ──────────────────────────────────────────────────────────

  test("pure produces a constant value without consuming input") {
    val p = Applicative[UriPath].pure(42)
    assertEquals(p.parse("anything"), Some((42, "anything")))
    assertEquals(p.template, "")
  }

  // ── real-world patterns ───────────────────────────────────────────

  test("pomodoro://timers/{name} pattern") {
    val p = UriPath.static("pomodoro://timers/") *> UriPath.named("name")
    assertEquals(p.template, "pomodoro://timers/{name}")
    assertEquals(p.parse("pomodoro://timers/deep-work"), Some(("deep-work", "")))
    assertEquals(p.parse("pomodoro://timers/"), None) // empty name
    assertEquals(p.parse("other://timers/x"), None)
  }

  test("notebook://{username}/{note_id} pattern") {
    val p = UriPath.static("notebook://") *>
      (UriPath.named("username"), UriPath.static("/") *> UriPath.named("note_id")).tupled
    assertEquals(p.template, "notebook://{username}/{note_id}")
    assertEquals(p.parse("notebook://alice/abc123"), Some((("alice", "abc123"), "")))
    assertEquals(p.parse("notebook://alice"), None)
  }

  test("app://items/{id} with Int conversion pattern") {
    val p = UriPath.static("app://items/") *> UriPath.named("id").as[Int]
    assertEquals(p.template, "app://items/{id}")
    assertEquals(p.parse("app://items/99"), Some((99, "")))
    assertEquals(p.parse("app://items/notanumber"), None)
  }
