package net.andimiller.mcp.core.protocol

import cats.Monoid

import io.circe.Decoder
import io.circe.Encoder

/** Behavioural hints clients use to decide how to surface a tool. All booleans default to the spec defaults when
  * omitted:
  *
  *   - `readOnlyHint` defaults to `false`
  *   - `destructiveHint` defaults to `true` (only meaningful when not read-only)
  *   - `idempotentHint` defaults to `false`
  *   - `openWorldHint` defaults to `true`
  *
  * `title` is the human-readable name; per spec, display-name precedence is `Tool.title` > `annotations.title` >
  * `Tool.name`.
  */
case class ToolAnnotations(
    title: Option[String] = None,
    readOnlyHint: Option[Boolean] = None,
    destructiveHint: Option[Boolean] = None,
    idempotentHint: Option[Boolean] = None,
    openWorldHint: Option[Boolean] = None
) derives Encoder.AsObject,
      Decoder:

  /** Materialise any unset hint to its spec default. Useful for clients that don't honour "field omitted = spec
    * default" and instead require every hint to be present on the wire.
    *
    * Note this is NOT the same as combining with [[ToolAnnotations.defaults]] via `|+|` — `defaults` would always win
    * conflicts under the Monoid's spec-default-wins rule, undoing any declared presets.
    */
  def withDefaults: ToolAnnotations =
    ToolAnnotations(
      title = title, readOnlyHint = readOnlyHint.orElse(Some(false)),
      destructiveHint = destructiveHint.orElse(Some(true)), idempotentHint = idempotentHint.orElse(Some(false)),
      openWorldHint = openWorldHint.orElse(Some(true))
    )

object ToolAnnotations:

  /** A read-only tool with no side effects. */
  val read: ToolAnnotations = ToolAnnotations(readOnlyHint = Some(true), destructiveHint = Some(false))

  /** A tool that mutates state but doesn't lose data. */
  val write: ToolAnnotations = ToolAnnotations(readOnlyHint = Some(false), destructiveHint = Some(false))

  /** A tool that mutates state in a way that may lose data (the spec default for non-read-only tools, stated explicitly
    * here for clarity).
    */
  val destroy: ToolAnnotations = ToolAnnotations(readOnlyHint = Some(false), destructiveHint = Some(true))

  /** A tool that can be safely retried with the same arguments. */
  val idempotent: ToolAnnotations = ToolAnnotations(idempotentHint = Some(true))

  /** A tool that interacts with a closed, well-defined system (overrides the default `openWorldHint = true`). */
  val closedWorld: ToolAnnotations = ToolAnnotations(openWorldHint = Some(false))

  /** A tool that interacts with an open system — external services, time-dependent state, etc. (the spec default,
    * stated explicitly here for clarity).
    */
  val openWorld: ToolAnnotations = ToolAnnotations(openWorldHint = Some(true))

  /** All four boolean hints materialised to their spec defaults. Suitable for clients that require every hint to be
    * present on the wire. Note: do NOT stack via `|+|` (`defaults |+| read` would undo `read` because the Monoid
    * resolves conflicts toward the spec default). Use [[ToolAnnotations.withDefaults]] instead to fill gaps after
    * combining declared presets.
    */
  val defaults: ToolAnnotations = ToolAnnotations(
    readOnlyHint = Some(false),
    destructiveHint = Some(true),
    idempotentHint = Some(false),
    openWorldHint = Some(true)
  )

  /** Combine two `Option[Boolean]` hint values. `None` always loses to a declared value; when both sides declare and
    * disagree, the spec default wins (which encodes "the safer assumption the client falls back to anyway"). This makes
    * the per-field combine commutative and associative, with `None` as identity.
    */
  private def combineHint(specDefault: Boolean)(a: Option[Boolean], b: Option[Boolean]): Option[Boolean] =
    (a, b) match
      case (None, x)                  => x
      case (x, None)                  => x
      case (Some(true), Some(true))   => Some(true)
      case (Some(false), Some(false)) => Some(false)
      case _                          => Some(specDefault)

  /** `Monoid[ToolAnnotations]` for stacking presets via `|+|`. Per-field rules:
    *
    *   - `readOnlyHint` (spec default `false`): conflict resolves to `false`
    *   - `destructiveHint` (spec default `true`): conflict resolves to `true`
    *   - `idempotentHint` (spec default `false`): conflict resolves to `false` ("not immutable + immutable = not
    *     immutable")
    *   - `openWorldHint` (spec default `true`): conflict resolves to `true` ("open world + not open world = open
    *     world")
    *   - `title`: rightmost non-empty wins (no truthiness ordering applies)
    *
    * Identity is `ToolAnnotations()` — all fields `None`.
    */
  given Monoid[ToolAnnotations] with

    def empty: ToolAnnotations = ToolAnnotations()

    def combine(a: ToolAnnotations, b: ToolAnnotations): ToolAnnotations =
      ToolAnnotations(
        title = b.title.orElse(a.title),
        readOnlyHint = combineHint(specDefault = false)(a.readOnlyHint, b.readOnlyHint),
        destructiveHint = combineHint(specDefault = true)(a.destructiveHint, b.destructiveHint),
        idempotentHint = combineHint(specDefault = false)(a.idempotentHint, b.idempotentHint),
        openWorldHint = combineHint(specDefault = true)(a.openWorldHint, b.openWorldHint)
      )

  /** Individual hint declarations, suitable for the varargs `.annotations(Hint.X, Hint.Y, ...)` builder form. Each case
    * maps to one of the preset vals (or `Title` for the non-boolean axis). Cases are folded via the existing
    * `Monoid[ToolAnnotations]`, so conflict resolution still follows the spec-default-wins rule.
    */
  enum Hint:

    /** `readOnlyHint=true`, `destructiveHint=false` — like [[read]]. */
    case Read

    /** `readOnlyHint=false`, `destructiveHint=false` — like [[write]]. */
    case Write

    /** `readOnlyHint=false`, `destructiveHint=true` — like [[destroy]]. */
    case Destroy

    /** `idempotentHint=true` — like [[idempotent]]. */
    case Idempotent

    /** `idempotentHint=false` (explicit, since `false` is the spec default and presets don't cover it). */
    case NotIdempotent

    /** `openWorldHint=true` — like [[openWorld]]. */
    case OpenWorld

    /** `openWorldHint=false` — like [[closedWorld]]. */
    case ClosedWorld

    /** `title=Some(value)` — the non-boolean axis. */
    case Title(value: String)

  object Hint:

    extension (h: Hint)

      def toToolAnnotations: ToolAnnotations = h match
        case Hint.Read          => read
        case Hint.Write         => write
        case Hint.Destroy       => destroy
        case Hint.Idempotent    => idempotent
        case Hint.NotIdempotent => ToolAnnotations(idempotentHint = Some(false))
        case Hint.OpenWorld     => openWorld
        case Hint.ClosedWorld   => closedWorld
        case Hint.Title(value)  => ToolAnnotations(title = Some(value))
