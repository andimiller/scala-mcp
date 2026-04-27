package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.ToolResult.given
import net.andimiller.mcp.core.schema.JsonSchema
import sttp.apispec.Schema
import sttp.apispec.circe.given

class Tool[F[_], Ctx, A, R](
    val name: String,
    val description: String,
    val inputSchema: Json,
    val outputSchema: Option[Json],
    val handle: (Ctx, A) => F[ToolResult[R]],
    val resultEncoder: Encoder.AsObject[R]
)(using val decoder: Decoder[A]):

  def provide(ctx: Ctx)(using Async[F]): Tool.Resolved[F] =
    val self = this
    given Encoder.AsObject[R] = self.resultEncoder
    new Tool.Resolved[F]:
      val name = self.name
      val description = self.description
      val inputSchema = self.inputSchema
      val outputSchema = self.outputSchema
      def handle(arguments: Json): F[CallToolResponse] =
        self.decoder.decodeJson(arguments) match
          case Right(a) => self.handle(ctx, a).map(ToolResult.toWire(_))
          case Left(err) =>
            Async[F].pure(ToolResult.toWire[R](ToolResult.Error(s"Invalid arguments: ${err.getMessage}")))

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
      new WithIn[Ctx, Unit, Nothing](n, s"Tool: $n", JsonSchema.obj().asJson, None)

  final class WithIn[Ctx, In, Out] private[ToolBuilder] (
      private[ToolBuilder] val toolName: String,
      private[ToolBuilder] val toolDescription: String,
      private[ToolBuilder] val inSchema: Json,
      private[ToolBuilder] val outSchemaJson: Option[Json]
  ):

    private def copy(
        toolName: String = this.toolName,
        toolDescription: String = this.toolDescription,
        inSchema: Json = this.inSchema,
        outSchemaJson: Option[Json] = this.outSchemaJson
    ): WithIn[Ctx, In, Out] =
      new WithIn[Ctx, In, Out](toolName, toolDescription, inSchema, outSchemaJson)

    def description(d: String): WithIn[Ctx, In, Out] =
      copy(toolDescription = d)

    def inputSchema(json: Json): WithIn[Ctx, In, Out] =
      copy(inSchema = json)

    def inputSchema(schema: Schema): WithIn[Ctx, In, Out] =
      copy(inSchema = schema.asJson)

    def outputSchema(json: Json): WithIn[Ctx, In, Out] =
      copy(outSchemaJson = Some(json))

    def outputSchema(schema: Schema): WithIn[Ctx, In, Out] =
      copy(outSchemaJson = Some(schema.asJson))

    def in[A](using JsonSchema[A], Decoder[A]): WithIn[Ctx, A, Out] =
      new WithIn[Ctx, A, Out](toolName, toolDescription, JsonSchema.toJson[A], outSchemaJson)

    /** Override the output schema with the schema derived from `R`. */
    def out[A: JsonSchema]: WithIn[Ctx, In, A] =
      new WithIn[Ctx, In, A](toolName, toolDescription, inSchema, outSchemaJson = Some(JsonSchema.toJson[A]))

    /** Promote a `Ctx = Unit` builder into one parameterised on a custom `Ctx`. */
    def contextual[A]: WithIn[A, In, Out] =
      new WithIn[A, In, Out](toolName, toolDescription, inSchema, outSchemaJson)

  // Terminal `run` / `runResult` are extension methods so the receiver type
  // (`WithIn[Ctx, A]` vs `WithIn[Unit, A]`) drives the dispatch — Scala 3 picks
  // by lambda arity without any `=:=` evidence.

  extension [Ctx, A, Out](b: WithIn[Ctx, A, Out])
    /** Build a tool whose handler returns a structured success value `R`. Sugar over
     *  [[runResult]] — the value is wrapped in `ToolResult.Success(...)`. */
    def run[F[_]: Async](handler: (Ctx, A) => F[Out])(using
        Decoder[A], Encoder.AsObject[Out], OutputSchema[Out]
    ): Tool[F, Ctx, A, Out] =
      b.runResult[F]((ctx, a) => handler(ctx, a).map(ToolResult.Success(_)))

    /** Build a tool whose handler returns a `ToolResult[R]` directly — useful when the
     *  handler needs to choose between structured success, plain text, or error branches.
     *  The output schema is auto-derived from `R` (via `OutputSchema[R]`) unless explicitly
     *  set via [[out]] / [[outputSchema]]. */
    def runResult[F[_]: Async](handler: (Ctx, A) => F[ToolResult[Out]])(using
        decoder: Decoder[A], encoder: Encoder.AsObject[Out], outSchema: OutputSchema[Out]
    ): Tool[F, Ctx, A, Out] =
      val finalOutput = b.outSchemaJson.orElse(outSchema.asJson)
      new Tool[F, Ctx, A, Out](
        name = b.toolName,
        description = b.toolDescription,
        inputSchema = b.inSchema,
        outputSchema = finalOutput,
        handle = handler,
        resultEncoder = encoder
      )(using decoder)

  extension [A, Out](b: WithIn[Unit, A, Out])
    /** Plain (`Ctx = Unit`) [[run]]: auto-calls `.provide(())` and returns a resolved
     *  handler ready to feed into `withTool`. */
    def run[F[_]: Async](handler: A => F[Out])(using
        Decoder[A], Encoder.AsObject[Out], OutputSchema[Out]
    ): Tool.Resolved[F] =
      b.runResult[F]((a: A) => handler(a).map(ToolResult.Success(_)))

    /** Plain (`Ctx = Unit`) [[runResult]]: auto-calls `.provide(())` and returns a resolved
     *  handler ready to feed into `withTool`. */
    def runResult[F[_]: Async](handler: A => F[ToolResult[Out]])(using
        decoder: Decoder[A], encoder: Encoder.AsObject[Out], outSchema: OutputSchema[Out]
    ): Tool.Resolved[F] =
      val finalOutput = b.outSchemaJson.orElse(outSchema.asJson)
      new Tool[F, Unit, A, Out](
        name = b.toolName,
        description = b.toolDescription,
        inputSchema = b.inSchema,
        outputSchema = finalOutput,
        handle = (_, a) => handler(a),
        resultEncoder = encoder
      )(using decoder).provide(())
