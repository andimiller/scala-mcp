package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import io.circe.Json
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.ToolResult.given

class Tool[F[_], Ctx, In, Out](
    val name: String,
    val description: String,
    val handle: (Ctx, In) => F[ToolResult[Out]],
)(using val in: InputSchema[In], val out: OutputSchema[Out]):

  def provide(ctx: Ctx)(using Async[F]): Tool.Resolved[F] =
    val self = this
    new Tool.Resolved[F]:
      val name = self.name
      val description = self.description
      val inputSchema = in.asJson
      val outputSchema = out.asJson
      def handle(arguments: Json): F[CallToolResponse] =
        in.decode(arguments) match
          case Right(a) => self.handle(ctx, a).map(ToolResult.toWire(_))
          case Left(err) =>
            Async[F].pure(ToolResult.toWire[Out](ToolResult.Error(s"Invalid arguments: ${err.getMessage}")))

object Tool:

  trait Resolved[F[_]]:
    def name: String
    def description: String
    def inputSchema: Json
    def outputSchema: Option[Json]
    def handle(arguments: Json): F[CallToolResponse]

  def builder: ToolBuilder.Empty[Unit] = new ToolBuilder.Empty[Unit]

  def contextual[Ctx]: ToolBuilder.Empty[Ctx] = new ToolBuilder.Empty[Ctx]

  /**
   * Build a [[Resolved]] from a [[ToolDefinition]] and a raw handler that produces a
   * `ToolResult[Nothing]`. Used by adapters (e.g. openapi-proxy) that supply their own
   * `outputSchema` on the definition and only emit `Raw`/`Text`/`Error` branches.
   */
  extension (td: ToolDefinition)
    def toResolved[F[_]: Async](handler: Json => F[ToolResult[Nothing]]): Resolved[F] =
      new Resolved[F]:
        val name = td.name
        val description = td.description
        val inputSchema = td.inputSchema
        val outputSchema = td.outputSchema
        def handle(arguments: Json) =
          handler(arguments).map(ToolResult.toWire[Nothing](_))

object ToolBuilder:

  final class Empty[Ctx]:
    def name(n: String): WithIn[Ctx, Unit, Nothing] =
      new WithIn[Ctx, Unit, Nothing](n, s"Tool: $n")

  final class WithIn[Ctx, In, Out] private[ToolBuilder] (
      private[ToolBuilder] val name: String,
      private[ToolBuilder] val description: String
  )(using val in: InputSchema[In], val out: OutputSchema[Out]):

    private def copy(name: String = this.name, description: String = this.description): WithIn[Ctx, In, Out] =
      new WithIn[Ctx, In, Out](name, description)

    def description(d: String): WithIn[Ctx, In, Out] =
      copy(description = d)

    def in[A](using InputSchema[A]): WithIn[Ctx, A, Out] =
      new WithIn[Ctx, A, Out](name, description)

    /** Override the output schema with the schema derived from `R`. */
    def out[A: OutputSchema]: WithIn[Ctx, In, A] =
      new WithIn[Ctx, In, A](name, description)

    /** Promote a `Ctx = Unit` builder into one parameterised on a custom `Ctx`. */
    def contextual[A]: WithIn[A, In, Out] =
      new WithIn[A, In, Out](name, description)

  extension [Ctx, In, Out](b: WithIn[Ctx, In, Out])
    /** Build a tool whose handler returns a structured success value `R`. Sugar over
     *  [[runResult]] — the value is wrapped in `ToolResult.Success(...)`. */
    def run[F[_]: Async](handler: (Ctx, In) => F[Out]): Tool[F, Ctx, In, Out] =
      b.runResult[F]((ctx, in) => handler(ctx, in).map(ToolResult.Success(_)))

    /** Build a tool whose handler returns a `ToolResult[R]` directly — useful when the
     *  handler needs to choose between structured success, plain text, or error branches.
     *  The output schema is auto-derived from `R` (via `OutputSchema[R]`) unless explicitly
     *  set via [[out]]. */
    def runResult[F[_]: Async](handler: (Ctx, In) => F[ToolResult[Out]]): Tool[F, Ctx, In, Out] =
      new Tool[F, Ctx, In, Out](b.name, b.description, handler)(using b.in, b.out)

  extension [In, Out](b: WithIn[Unit, In, Out])
    /** Plain (`Ctx = Unit`) [[run]]: auto-calls `.provide(())` and returns a resolved
     *  handler ready to feed into `withTool`. */
    def run[F[_]: Async](handler: In => F[Out]): Tool.Resolved[F] =
      b.runResult[F]((in: In) => handler(in).map(ToolResult.Success(_)))

    /** Plain (`Ctx = Unit`) [[runResult]]: auto-calls `.provide(())` and returns a resolved
     *  handler ready to feed into `withTool`. */
    def runResult[F[_]: Async](handler: In => F[ToolResult[Out]]): Tool.Resolved[F] =
      new Tool[F, Unit, In, Out](
        name = b.name,
        description = b.description,
        handle = (_, in) => handler(in),
      )(using b.in, b.out).provide(())
