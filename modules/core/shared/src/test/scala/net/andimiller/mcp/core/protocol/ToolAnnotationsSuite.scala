package net.andimiller.mcp.core.protocol

import cats.Monoid
import cats.effect.IO
import cats.syntax.semigroup.*

import net.andimiller.mcp.core.protocol.ToolAnnotations.Hint
import net.andimiller.mcp.core.protocol.ToolAnnotations.Hint.toToolAnnotations
import net.andimiller.mcp.core.server.tool

import munit.FunSuite

class ToolAnnotationsSuite extends FunSuite:

  private val M = summon[Monoid[ToolAnnotations]]

  test("empty is the identity") {
    val a = ToolAnnotations(readOnlyHint = Some(true), idempotentHint = Some(true))
    assertEquals(M.empty |+| a, a)
    assertEquals(a |+| M.empty, a)
  }

  test("None on either side loses to a declared value") {
    val a        = ToolAnnotations(readOnlyHint = Some(true))
    val b        = ToolAnnotations(idempotentHint = Some(true))
    val combined = a |+| b
    assertEquals(combined.readOnlyHint, Some(true))
    assertEquals(combined.idempotentHint, Some(true))
  }

  test("readOnlyHint conflict resolves to false (spec default)") {
    val combined = ToolAnnotations(readOnlyHint = Some(true)) |+| ToolAnnotations(readOnlyHint = Some(false))
    assertEquals(combined.readOnlyHint, Some(false))
    val flipped = ToolAnnotations(readOnlyHint = Some(false)) |+| ToolAnnotations(readOnlyHint = Some(true))
    assertEquals(flipped.readOnlyHint, Some(false))
  }

  test("destructiveHint conflict resolves to true (spec default)") {
    val combined = ToolAnnotations(destructiveHint = Some(true)) |+| ToolAnnotations(destructiveHint = Some(false))
    assertEquals(combined.destructiveHint, Some(true))
    val flipped = ToolAnnotations(destructiveHint = Some(false)) |+| ToolAnnotations(destructiveHint = Some(true))
    assertEquals(flipped.destructiveHint, Some(true))
  }

  test("idempotentHint conflict resolves to false (not immutable + immutable = not immutable)") {
    val combined = ToolAnnotations(idempotentHint = Some(true)) |+| ToolAnnotations(idempotentHint = Some(false))
    assertEquals(combined.idempotentHint, Some(false))
  }

  test("openWorldHint conflict resolves to true (open world + not open world = open world)") {
    val combined = ToolAnnotations(openWorldHint = Some(true)) |+| ToolAnnotations(openWorldHint = Some(false))
    assertEquals(combined.openWorldHint, Some(true))
  }

  test("title takes the rightmost non-empty value") {
    val a = ToolAnnotations(title = Some("A"))
    val b = ToolAnnotations(title = Some("B"))
    assertEquals((a |+| b).title, Some("B"))
    assertEquals((a |+| ToolAnnotations()).title, Some("A"))
    assertEquals((ToolAnnotations() |+| b).title, Some("B"))
  }

  test("combine is commutative on boolean fields") {
    val a = ToolAnnotations(readOnlyHint = Some(true), destructiveHint = Some(false), idempotentHint = Some(true))
    val b = ToolAnnotations(readOnlyHint = Some(false), idempotentHint = Some(false), openWorldHint = Some(false))
    assertEquals((a |+| b).copy(title = None), (b |+| a).copy(title = None))
  }

  test("combine is associative") {
    val a = ToolAnnotations(readOnlyHint = Some(true))
    val b = ToolAnnotations(destructiveHint = Some(false), idempotentHint = Some(true))
    val c = ToolAnnotations(openWorldHint = Some(false), readOnlyHint = Some(false))
    assertEquals((a |+| b) |+| c, a |+| (b |+| c))
  }

  test("preset stacks: read |+| closedWorld") {
    val combined = ToolAnnotations.read |+| ToolAnnotations.closedWorld
    assertEquals(combined.readOnlyHint, Some(true))
    assertEquals(combined.destructiveHint, Some(false))
    assertEquals(combined.openWorldHint, Some(false))
    assertEquals(combined.idempotentHint, None)
  }

  test("defaults exposes the spec-default value for every boolean hint") {
    val d = ToolAnnotations.defaults
    assertEquals(d.readOnlyHint, Some(false))
    assertEquals(d.destructiveHint, Some(true))
    assertEquals(d.idempotentHint, Some(false))
    assertEquals(d.openWorldHint, Some(true))
  }

  test("withDefaults fills only the missing fields") {
    val base   = ToolAnnotations.read |+| ToolAnnotations.closedWorld
    val filled = base.withDefaults
    // declared values survive
    assertEquals(filled.readOnlyHint, Some(true))
    assertEquals(filled.destructiveHint, Some(false))
    assertEquals(filled.openWorldHint, Some(false))
    // previously-unset fields gain the spec default
    assertEquals(filled.idempotentHint, Some(false))
  }

  test("defaults.withDefaults is a no-op (idempotent)") {
    assertEquals(ToolAnnotations.defaults.withDefaults, ToolAnnotations.defaults)
  }

  test("Hint folding matches |+| composition of the same presets") {
    val viaHints = List(Hint.Write, Hint.Idempotent, Hint.ClosedWorld)
      .foldLeft(M.empty)(_ |+| _.toToolAnnotations)
    val viaPresets = ToolAnnotations.write |+| ToolAnnotations.idempotent |+| ToolAnnotations.closedWorld
    assertEquals(viaHints, viaPresets)
  }

  test("varargs builder applies .withDefaults so every boolean hint is materialised") {
    val resolved = tool
      .name("t")
      .annotations(Hint.Destroy, Hint.ClosedWorld)
      .in[Unit]
      .runResult[IO](_ => IO.pure(ToolResult.Text("x")))
    val annotations = resolved.annotations.getOrElse(fail("expected annotations to be set"))
    // declared hints preserved
    assertEquals(annotations.readOnlyHint, Some(false))
    assertEquals(annotations.destructiveHint, Some(true))
    assertEquals(annotations.openWorldHint, Some(false))
    // gap filled with spec default
    assertEquals(annotations.idempotentHint, Some(false))
  }

  test("varargs builder with no hints writes the full spec-defaults block") {
    val resolved = tool
      .name("t")
      .annotations()
      .in[Unit]
      .runResult[IO](_ => IO.pure(ToolResult.Text("x")))
    val annotations = resolved.annotations.getOrElse(fail("expected annotations to be set"))
    assertEquals(annotations, ToolAnnotations.defaults)
  }

  test("Hint.NotIdempotent declares idempotentHint = Some(false)") {
    assertEquals(Hint.NotIdempotent.toToolAnnotations.idempotentHint, Some(false))
  }

  test("Hint.Title carries the title through folding") {
    val combined = List(Hint.Read, Hint.Title("Delete user"))
      .foldLeft(M.empty)(_ |+| _.toToolAnnotations)
    assertEquals(combined.title, Some("Delete user"))
    assertEquals(combined.readOnlyHint, Some(true))
  }

  test("preset stacks: write |+| idempotent |+| closedWorld (pomodoro pause_timer)") {
    val combined = ToolAnnotations.write |+| ToolAnnotations.idempotent |+| ToolAnnotations.closedWorld
    assertEquals(combined.readOnlyHint, Some(false))
    assertEquals(combined.destructiveHint, Some(false))
    assertEquals(combined.idempotentHint, Some(true))
    assertEquals(combined.openWorldHint, Some(false))
  }
