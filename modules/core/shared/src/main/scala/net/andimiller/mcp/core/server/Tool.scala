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
    import cats.syntax.functor.*
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

  def builder[F[_]: Async]: ToolBuilder.PlainEmpty[F] = new ToolBuilder.PlainEmpty[F]

  def contextual[F[_]: Async, Ctx]: ToolBuilder.ContextualEmpty[F, Ctx] =
    new ToolBuilder.ContextualEmpty[F, Ctx]

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
          import cats.syntax.functor.*
          handler(arguments).map(ToolResult.toWire[Nothing](_))

  extension [F[_]: Async, A, R](tool: Tool[F, Unit, A, R])
    def resolve: Resolved[F] = tool.provide(())

object ToolBuilder:

  // ── Context-free (plain) builder ──────────────────────────────

  final class PlainEmpty[F[_]: Async]:
    def name(n: String): PlainBuilder[F] =
      new PlainBuilder[F](n, s"Tool: $n", None, None)

  final class PlainBuilder[F[_]: Async] private[ToolBuilder] (
      toolName: String,
      toolDescription: String,
      inputSchemaJson: Option[Json],
      outputSchemaJson: Option[Json]
  ):

    private def copy(
        toolName: String = this.toolName,
        toolDescription: String = this.toolDescription,
        inputSchemaJson: Option[Json] = this.inputSchemaJson,
        outputSchemaJson: Option[Json] = this.outputSchemaJson
    ): PlainBuilder[F] =
      new PlainBuilder[F](toolName, toolDescription, inputSchemaJson, outputSchemaJson)

    def description(d: String): PlainBuilder[F] =
      copy(toolDescription = d)

    def inputSchema(json: Json): PlainBuilder[F] =
      copy(inputSchemaJson = Some(json))

    def outputSchema(json: Json): PlainBuilder[F] =
      copy(outputSchemaJson = Some(json))

    def inputSchema(schema: Schema): PlainBuilder[F] =
      copy(inputSchemaJson = Some(schema.asJson))

    def outputSchema(schema: Schema): PlainBuilder[F] =
      copy(outputSchemaJson = Some(schema.asJson))

    def in[A](using JsonSchema[A], Decoder[A]): PlainIn[F, A] =
      new PlainIn[F, A](toolName, toolDescription, JsonSchema.toJson[A], outputSchemaJson)

    /** Shortcut for `.in[A].runResult(...)`. The handler returns a `ToolResult[Nothing]` —
     *  i.e. only `Text`/`Error`/`Raw` branches, no structured success — so no output
     *  schema is advertised. */
    def logic[A](handler: A => F[ToolResult[Nothing]])(using JsonSchema[A], Decoder[A]): Tool[F, Unit, A, Nothing] =
      new Tool[F, Unit, A, Nothing](
        name = toolName,
        description = toolDescription,
        inputSchema = JsonSchema.toJson[A],
        outputSchema = None,
        handle = (_, a) => handler(a),
        resultEncoder = summon[Encoder.AsObject[Nothing]]
      )(using summon[Decoder[A]])

  final class PlainIn[F[_]: Async, A] private[ToolBuilder] (
      toolName: String,
      toolDescription: String,
      inSchema: Json,
      outSchemaJson: Option[Json]
  ):

    private def copy(
        toolName: String = this.toolName,
        toolDescription: String = this.toolDescription,
        inSchema: Json = this.inSchema,
        outSchemaJson: Option[Json] = this.outSchemaJson
    ): PlainIn[F, A] =
      new PlainIn[F, A](toolName, toolDescription, inSchema, outSchemaJson)

    def description(d: String): PlainIn[F, A] =
      copy(toolDescription = d)

    /** Override the output schema with the schema derived from `R`. Useful when the runtime
     *  result type and the advertised schema differ. */
    def out[R: JsonSchema]: PlainIn[F, A] =
      copy(outSchemaJson = Some(JsonSchema.toJson[R]))

    /** Build a tool whose handler returns a structured success value `R`. Sugar over
     *  [[runResult]] — the value is wrapped in `ToolResult.Success(...)`. */
    def run[R](handler: A => F[R])(using Decoder[A], Encoder.AsObject[R], OutputSchema[R]): Tool[F, Unit, A, R] =
      runResult[R](a => handler(a).map(ToolResult.Success(_)))

    /** Build a tool whose handler returns a `ToolResult[R]` directly — useful when the
     *  handler needs to choose between structured success, plain text, or error branches.
     *  The output schema is auto-derived from `R` (via `OutputSchema[R]`) unless explicitly
     *  set via [[out]] / [[outputSchema]]. */
    def runResult[R](handler: A => F[ToolResult[R]])(using
        Decoder[A], Encoder.AsObject[R], OutputSchema[R]
    ): Tool[F, Unit, A, R] =
      val finalOutput = outSchemaJson.orElse(summon[OutputSchema[R]].asJson)
      new Tool[F, Unit, A, R](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = (_, a) => handler(a),
        resultEncoder = summon[Encoder.AsObject[R]]
      )(using summon[Decoder[A]])

    def contextual[Ctx]: ContextualIn[F, Ctx, A] =
      new ContextualIn[F, Ctx, A](toolName, toolDescription, inSchema, outSchemaJson)

  // ── Contextual builder ──────────────────────────────────────────

  final class ContextualEmpty[F[_]: Async, Ctx]:
    def name(n: String): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](n, s"Tool: $n", None, None)

  final class ContextualBuilder[F[_]: Async, Ctx] private[ToolBuilder] (
      toolName: String,
      toolDescription: String,
      inputSchemaJson: Option[Json],
      outputSchemaJson: Option[Json]
  ):

    private def copy(
        toolName: String = this.toolName,
        toolDescription: String = this.toolDescription,
        inputSchemaJson: Option[Json] = this.inputSchemaJson,
        outputSchemaJson: Option[Json] = this.outputSchemaJson
    ): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](toolName, toolDescription, inputSchemaJson, outputSchemaJson)

    def description(d: String): ContextualBuilder[F, Ctx] =
      copy(toolDescription = d)

    def inputSchema(json: Json): ContextualBuilder[F, Ctx] =
      copy(inputSchemaJson = Some(json))

    def outputSchema(json: Json): ContextualBuilder[F, Ctx] =
      copy(outputSchemaJson = Some(json))

    def inputSchema(schema: Schema): ContextualBuilder[F, Ctx] =
      copy(inputSchemaJson = Some(schema.asJson))

    def outputSchema(schema: Schema): ContextualBuilder[F, Ctx] =
      copy(outputSchemaJson = Some(schema.asJson))

    def in[A](using JsonSchema[A], Decoder[A]): ContextualIn[F, Ctx, A] =
      new ContextualIn[F, Ctx, A](toolName, toolDescription, JsonSchema.toJson[A], outputSchemaJson)

  final class ContextualIn[F[_]: Async, Ctx, A] private[ToolBuilder] (
      toolName: String,
      toolDescription: String,
      inSchema: Json,
      outSchemaJson: Option[Json]
  ):

    private def copy(
        toolName: String = this.toolName,
        toolDescription: String = this.toolDescription,
        inSchema: Json = this.inSchema,
        outSchemaJson: Option[Json] = this.outSchemaJson
    ): ContextualIn[F, Ctx, A] =
      new ContextualIn[F, Ctx, A](toolName, toolDescription, inSchema, outSchemaJson)

    def description(d: String): ContextualIn[F, Ctx, A] =
      copy(toolDescription = d)

    /** Override the output schema with the schema derived from `R`. */
    def out[R: JsonSchema]: ContextualIn[F, Ctx, A] =
      copy(outSchemaJson = Some(JsonSchema.toJson[R]))

    /** Build a tool whose handler returns a structured success value `R`. Sugar over
     *  [[runResult]] — the value is wrapped in `ToolResult.Success(...)`. */
    def run[R](handler: (Ctx, A) => F[R])(using Decoder[A], Encoder.AsObject[R], OutputSchema[R]): Tool[F, Ctx, A, R] =
      runResult[R]((ctx, a) => handler(ctx, a).map(ToolResult.Success(_)))

    /** Build a tool whose handler returns a `ToolResult[R]` directly — useful when the
     *  handler needs to choose between structured success, plain text, or error branches.
     *  The output schema is auto-derived from `R` (via `OutputSchema[R]`) unless explicitly
     *  set via [[out]] / [[outputSchema]]. */
    def runResult[R](handler: (Ctx, A) => F[ToolResult[R]])(using
        Decoder[A], Encoder.AsObject[R], OutputSchema[R]
    ): Tool[F, Ctx, A, R] =
      val finalOutput = outSchemaJson.orElse(summon[OutputSchema[R]].asJson)
      new Tool[F, Ctx, A, R](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = handler,
        resultEncoder = summon[Encoder.AsObject[R]]
      )(using summon[Decoder[A]])