package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.ToolAnnotations.Hint
import net.andimiller.mcp.core.protocol.ToolAnnotations.Hint.toToolAnnotations

import io.circe.Json
import io.circe.JsonObject

/** A tool that the server can dispatch to. Carries all the wire-level metadata used to render the entry in `tools/list`
  * plus the executable handler, which receives the full [[ToolCallContext]] (so it can read the per-session `Ctx`, the
  * session id, and the request's `_meta`).
  *
  * Authors normally build instances via [[Tool.builder]] / [[Tool.contextual]]; adapters that already have a
  * `ToolDefinition` plus a raw JSON-in/JSON-out handler can use [[Tool.toTool]].
  */
trait Tool[F[_], Ctx]:

  def name: String

  def description: String

  def inputSchema: Json

  def outputSchema: Option[Json]

  def title: Option[String] = None

  def icons: List[Icon] = Nil

  def annotations: Option[ToolAnnotations] = None

  def execution: Option[ToolExecution] = None

  def meta: Option[JsonObject] = None

  /** Per-tool middleware. Composed INSIDE server-wide middlewares at dispatch time. First registered = outermost within
    * this list.
    */
  def middleware: List[ToolMiddleware[F, Ctx]] = Nil

  /** Optional visibility predicate. `None` means the tool is always visible. When `Some(p)`, the tool appears in
    * `tools/list` only when `p(ctx)` evaluates to `true`, and `tools/call` for it is rejected as "not found" when it
    * does not. Evaluated per session against the server's `Ctx`.
    */
  def visible: Option[Ctx => F[Boolean]] = None

  def handle(call: ToolCallContext[F, Ctx]): F[CallToolResponse]

  /** Append a middleware to the [[middleware]] list. Returns a new `Tool[F, Ctx]` that delegates to this one but
    * reports the extended list.
    */
  def withMiddleware(mw: ToolMiddleware[F, Ctx]): Tool[F, Ctx] =
    val self = this
    new Tool[F, Ctx]:
      val name                                                       = self.name
      val description                                                = self.description
      val inputSchema                                                = self.inputSchema
      val outputSchema                                               = self.outputSchema
      override val title                                             = self.title
      override val icons                                             = self.icons
      override val annotations                                       = self.annotations
      override val execution                                         = self.execution
      override val meta                                              = self.meta
      override val middleware                                        = self.middleware :+ mw
      override val visible                                           = self.visible
      def handle(call: ToolCallContext[F, Ctx]): F[CallToolResponse] = self.handle(call)

object Tool:

  def builder: ToolBuilder.Empty[Unit] = new ToolBuilder.Empty[Unit]

  def contextual[Ctx]: ToolBuilder.Empty[Ctx] = new ToolBuilder.Empty[Ctx]

  /** Build a `Tool[F, Ctx]` from a [[ToolDefinition]] and a raw handler that produces a `ToolResult[Nothing]`. Used by
    * adapters (e.g. openapi-proxy) that supply their own `outputSchema` on the definition and only emit
    * `Raw`/`Text`/`Error` branches. The handler ignores `Ctx` (it isn't given one).
    */
  extension (td: ToolDefinition)

    def toTool[F[_]: Async, Ctx](handler: Json => F[ToolResult[Nothing]]): Tool[F, Ctx] =
      new Tool[F, Ctx]:
        val name                                                       = td.name
        val description                                                = td.description.getOrElse(s"Tool: ${td.name}")
        val inputSchema                                                = td.inputSchema
        val outputSchema                                               = td.outputSchema
        override val title                                             = td.title
        override val icons                                             = td.icons.getOrElse(Nil)
        override val annotations                                       = td.annotations
        override val execution                                         = td.execution
        override val meta                                              = td._meta
        def handle(call: ToolCallContext[F, Ctx]): F[CallToolResponse] =
          handler(call.request.arguments).map(ToolResult.toWire[Nothing](_))

object ToolBuilder:

  /** Internal sum used to capture a tool's visibility rule on the builder before `F` is known. Materialised into a
    * concrete `Ctx => F[Boolean]` by `fromBuilder` once the handler picks an `F`.
    */
  private[server] sealed trait VisibilityRule[Ctx]
  private[server] object VisibilityRule:
    final case class Always[Ctx]()                 extends VisibilityRule[Ctx]
    final case class Pure[Ctx](p: Ctx => Boolean)  extends VisibilityRule[Ctx]
    /** Effectful predicate stored type-erased — the user's `F[Boolean]` is the same `F` chosen at `.run` time, so the
      * cast in `fromBuilder` is safe under that consistency.
      */
    final case class Effectful[Ctx](p: Ctx => Any) extends VisibilityRule[Ctx]

  final class Empty[Ctx]:

    def name(n: String): WithIn[Ctx, Unit, Nothing] =
      new WithIn[Ctx, Unit, Nothing](n, s"Tool: $n")

  final class WithIn[Ctx, In, Out] private[ToolBuilder] (
      private[ToolBuilder] val name: String,
      private[ToolBuilder] val description: String,
      private[ToolBuilder] val title: Option[String] = None,
      private[ToolBuilder] val icons: List[Icon] = Nil,
      private[ToolBuilder] val annotations: Option[ToolAnnotations] = None,
      private[ToolBuilder] val execution: Option[ToolExecution] = None,
      private[ToolBuilder] val meta: Option[JsonObject] = None,
      private[ToolBuilder] val visibility: VisibilityRule[Ctx] = VisibilityRule.Always[Ctx]()
  )(using val in: InputSchema[In], val out: OutputSchema[Out]):

    private def copy(
        name: String = this.name,
        description: String = this.description,
        title: Option[String] = this.title,
        icons: List[Icon] = this.icons,
        annotations: Option[ToolAnnotations] = this.annotations,
        execution: Option[ToolExecution] = this.execution,
        meta: Option[JsonObject] = this.meta,
        visibility: VisibilityRule[Ctx] = this.visibility
    ): WithIn[Ctx, In, Out] =
      new WithIn[Ctx, In, Out](name, description, title, icons, annotations, execution, meta, visibility)

    def description(d: String): WithIn[Ctx, In, Out] =
      copy(description = d)

    def in[A](using InputSchema[A]): WithIn[Ctx, A, Out] =
      new WithIn[Ctx, A, Out](name, description, title, icons, annotations, execution, meta, visibility)

    /** Override the output schema with the schema derived from `R`. */
    def out[A: OutputSchema]: WithIn[Ctx, In, A] =
      new WithIn[Ctx, In, A](name, description, title, icons, annotations, execution, meta, visibility)

    /** Promote a `Ctx = Unit` builder into one parameterised on a custom `Ctx`. Drops any visibility rule — a `Ctx = Unit`
      * rule isn't meaningful against a non-Unit Ctx — so re-declare visibility on the new builder if you need it.
      */
    def contextual[A]: WithIn[A, In, Out] =
      new WithIn[A, In, Out](name, description, title, icons, annotations, execution, meta)

    /** Restrict the tool's visibility with a pure predicate against the server's `Ctx`. The tool appears in
      * `tools/list` and accepts `tools/call` only when `p(ctx) == true`. Replaces any previously-set visibility rule.
      */
    def visibleWhen(p: Ctx => Boolean): WithIn[Ctx, In, Out] =
      copy(visibility = VisibilityRule.Pure(p))

    /** Restrict the tool's visibility with an effectful predicate. The `F` is inferred from the predicate's return type
      * and must match the `F` selected at `.run` / `.runResult` time. Replaces any previously-set visibility rule.
      */
    def visibleWhenF[F[_]](p: Ctx => F[Boolean]): WithIn[Ctx, In, Out] =
      copy(visibility = VisibilityRule.Effectful(ctx => p(ctx).asInstanceOf[Any]))

    def title(t: String): WithIn[Ctx, In, Out] = copy(title = Some(t))

    /** Append a single icon. */
    def icon(i: Icon): WithIn[Ctx, In, Out] = copy(icons = icons :+ i)

    /** Replace the icon list. */
    def icons(xs: List[Icon]): WithIn[Ctx, In, Out] = copy(icons = xs)

    def annotations(a: ToolAnnotations): WithIn[Ctx, In, Out] = copy(annotations = Some(a))

    /** Snappy varargs form: pass zero or more [[Hint]]s, fold them via the existing `Monoid[ToolAnnotations]`, and call
      * `.withDefaults` so the wire output materialises every boolean hint (handy for clients that don't honour "omitted =
      * spec default"). Calling with no args (`.annotations()`) writes the full spec-defaults block. Falls back to the
      * explicit `annotations(a: ToolAnnotations)` overload if you want control over which fields are materialised.
      */
    def annotations(hints: Hint*): WithIn[Ctx, In, Out] =
      val combined = hints.foldLeft(ToolAnnotations())(_ |+| _.toToolAnnotations)
      annotations(combined.withDefaults)

    def execution(e: ToolExecution): WithIn[Ctx, In, Out] = copy(execution = Some(e))

    /** Set the full `_meta` object, replacing any previous value. */
    def meta(m: JsonObject): WithIn[Ctx, In, Out] = copy(meta = Some(m))

    /** Add a single `_meta` key. */
    def meta(key: String, value: Json): WithIn[Ctx, In, Out] =
      val base = meta.getOrElse(JsonObject.empty)
      copy(meta = Some(base.add(key, value)))

    /** Bulk-apply the cross-cutting metadata subset (title/icons/_meta). Last-write-wins: overwrites whatever is
      * currently set on the builder.
      */
    def metadata(m: Metadata): WithIn[Ctx, In, Out] =
      copy(title = m.title, icons = m.icons, meta = m.meta)

  extension [Ctx, In, Out](b: WithIn[Ctx, In, Out])

    /** Build a tool whose handler returns a structured success value `R`. Sugar over [[runResult]] — the value is
      * wrapped in `ToolResult.Success(...)`.
      */
    def run[F[_]: Async](handler: (Ctx, In) => F[Out]): Tool[F, Ctx] =
      b.runResult[F]((ctx, in) => handler(ctx, in).map(ToolResult.Success(_)))

    /** Build a tool whose handler returns a `ToolResult[R]` directly — useful when the handler needs to choose between
      * structured success, plain text, or error branches. The output schema is auto-derived from `R` (via
      * `OutputSchema[R]`) unless explicitly set via [[out]].
      */
    def runResult[F[_]: Async](handler: (Ctx, In) => F[ToolResult[Out]]): Tool[F, Ctx] =
      fromBuilder(b, handler)

  extension [In, Out](b: WithIn[Unit, In, Out])

    /** Plain (`Ctx = Unit`) [[run]]. */
    def run[F[_]: Async](handler: In => F[Out]): Tool[F, Unit] =
      b.runResult[F]((in: In) => handler(in).map(ToolResult.Success(_)))

    /** Plain (`Ctx = Unit`) [[runResult]]. */
    def runResult[F[_]: Async](handler: In => F[ToolResult[Out]]): Tool[F, Unit] =
      fromBuilder[F, Unit, In, Out](b, (_, in) => handler(in))

  /** Internal constructor that erases `In`/`Out` while preserving the metadata captured on the builder. */
  private def fromBuilder[F[_]: Async, Ctx, In, Out](
      b: WithIn[Ctx, In, Out],
      handler: (Ctx, In) => F[ToolResult[Out]]
  ): Tool[F, Ctx] =
    given InputSchema[In]   = b.in
    given OutputSchema[Out] = b.out
    val materialisedVisible: Option[Ctx => F[Boolean]] = b.visibility match
      case VisibilityRule.Always()     => None
      case VisibilityRule.Pure(p)      => Some(ctx => Async[F].pure(p(ctx)))
      case VisibilityRule.Effectful(p) => Some(ctx => p(ctx).asInstanceOf[F[Boolean]])
    new Tool[F, Ctx]:
      val name                                                       = b.name
      val description                                                = b.description
      val inputSchema                                                = summon[InputSchema[In]].asJson
      val outputSchema                                               = summon[OutputSchema[Out]].asJson
      override val title                                             = b.title
      override val icons                                             = b.icons
      override val annotations                                       = b.annotations
      override val execution                                         = b.execution
      override val meta                                              = b.meta
      override val visible                                           = materialisedVisible
      def handle(call: ToolCallContext[F, Ctx]): F[CallToolResponse] =
        summon[InputSchema[In]].decode(call.request.arguments) match
          case Right(a)  => handler(call.ctx, a).map(ToolResult.toWire(_))
          case Left(err) =>
            Async[F].pure(ToolResult.toWire[Out](ToolResult.Error(s"Invalid arguments: ${err.getMessage}")))
