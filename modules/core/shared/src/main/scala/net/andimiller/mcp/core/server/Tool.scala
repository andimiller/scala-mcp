package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.schema.JsonSchema
import sttp.apispec.Schema
import sttp.apispec.circe.given

class Tool[F[_], Ctx, A, R](
    val name: String,
    val description: String,
    val inputSchema: Json,
    val outputSchema: Json,
    val handle: (Ctx, A, RequestContext[F]) => F[R],
    val resultEncoder: R => ToolResult
)(using val decoder: Decoder[A]):

  def provide(ctx: Ctx)(using Async[F]): Tool.Resolved[F] =
    val self = this
    import cats.syntax.functor.*
    new Tool.Resolved[F]:
      val name = self.name
      val description = self.description
      val inputSchema = self.inputSchema
      val outputSchema = self.outputSchema
      def handle(arguments: Json, rc: RequestContext[F]): F[ToolResult] =
        self.decoder.decodeJson(arguments) match
          case Right(a)  => self.handle(ctx, a, rc).map(self.resultEncoder)
          case Left(err) => Async[F].pure(ToolResult.error(s"Invalid arguments: ${err.getMessage}"))

object Tool:

  trait Resolved[F[_]]:
    def name: String
    def description: String
    def inputSchema: Json
    def outputSchema: Json
    def handle(arguments: Json, rc: RequestContext[F]): F[ToolResult]

  def builder[F[_]: Async]: ToolBuilder.PlainEmpty[F] = new ToolBuilder.PlainEmpty[F]

  def contextual[F[_]: Async, Ctx]: ToolBuilder.ContextualEmpty[F, Ctx] =
    new ToolBuilder.ContextualEmpty[F, Ctx]

  extension (td: ToolDefinition)
    def toResolved[F[_]](handler: (Json, RequestContext[F]) => F[ToolResult]): Resolved[F] =
      new Resolved[F]:
        val name = td.name
        val description = td.description
        val inputSchema = td.inputSchema
        val outputSchema = td.outputSchema
        def handle(arguments: Json, rc: RequestContext[F]) = handler(arguments, rc)

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

    def logic[A](handler: A => F[ToolResult])(using JsonSchema[A], Decoder[A]): Tool[F, Unit, A, ToolResult] =
      new Tool[F, Unit, A, ToolResult](
        name = toolName,
        description = toolDescription,
        inputSchema = JsonSchema.toJson[A],
        outputSchema = JsonSchema.toJson[Unit],
        handle = (_, a, _) => handler(a),
        resultEncoder = identity
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

    def out[R: Encoder.AsObject: JsonSchema]: PlainIn[F, A] =
      copy(outSchemaJson = Some(JsonSchema.toJson[R]))

    def run[R](handler: A => F[R])(using Decoder[A], Encoder.AsObject[R], JsonSchema[R]): Tool[F, Unit, A, R] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[R])
      new Tool[F, Unit, A, R](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = (_, a, _) => handler(a),
        resultEncoder = r => ToolResult.structured(r.asJson)
      )(using summon[Decoder[A]])

    def runWithContext[R](handler: (A, RequestContext[F]) => F[R])(using Decoder[A], Encoder.AsObject[R], JsonSchema[R]): Tool[F, Unit, A, R] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[R])
      new Tool[F, Unit, A, R](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = (_, a, rc) => handler(a, rc),
        resultEncoder = r => ToolResult.structured(r.asJson)
      )(using summon[Decoder[A]])

    def handle(handler: A => F[ToolResult])(using Decoder[A]): Tool[F, Unit, A, ToolResult] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[Unit])
      new Tool[F, Unit, A, ToolResult](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = (_, a, _) => handler(a),
        resultEncoder = identity
      )(using summon[Decoder[A]])

    def handleWithContext(handler: (A, RequestContext[F]) => F[ToolResult])(using Decoder[A]): Tool[F, Unit, A, ToolResult] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[Unit])
      new Tool[F, Unit, A, ToolResult](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = (_, a, rc) => handler(a, rc),
        resultEncoder = identity
      )(using summon[Decoder[A]])

    def contextual[Ctx]: ContextualIn[F, Ctx, A] =
      new ContextualIn[F, Ctx, A](toolName, toolDescription, inSchema, outSchemaJson, None)

  // ── Contextual builder ──────────────────────────────────────────

  final class ContextualEmpty[F[_]: Async, Ctx]:
    def name(n: String): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](n, s"Tool: $n", None, None, None)

  final class ContextualBuilder[F[_]: Async, Ctx] private[ToolBuilder] (
      toolName: String,
      toolDescription: String,
      inputSchemaJson: Option[Json],
      outputSchemaJson: Option[Json],
      isStructured: Option[Boolean]
  ):

    private def copy(
        toolName: String = this.toolName,
        toolDescription: String = this.toolDescription,
        inputSchemaJson: Option[Json] = this.inputSchemaJson,
        outputSchemaJson: Option[Json] = this.outputSchemaJson,
        isStructured: Option[Boolean] = this.isStructured
    ): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](toolName, toolDescription, inputSchemaJson, outputSchemaJson, isStructured)

    def description(d: String): ContextualBuilder[F, Ctx] =
      copy(toolDescription = d)

    def inputSchema(json: Json): ContextualBuilder[F, Ctx] =
      copy(inputSchemaJson = Some(json))

    def outputSchema(json: Json): ContextualBuilder[F, Ctx] =
      copy(outputSchemaJson = Some(json), isStructured = Some(true))

    def inputSchema(schema: Schema): ContextualBuilder[F, Ctx] =
      copy(inputSchemaJson = Some(schema.asJson))

    def outputSchema(schema: Schema): ContextualBuilder[F, Ctx] =
      copy(outputSchemaJson = Some(schema.asJson), isStructured = Some(true))

    def in[A](using JsonSchema[A], Decoder[A]): ContextualIn[F, Ctx, A] =
      new ContextualIn[F, Ctx, A](toolName, toolDescription, JsonSchema.toJson[A], outputSchemaJson, isStructured)

  final class ContextualIn[F[_]: Async, Ctx, A] private[ToolBuilder] (
      toolName: String,
      toolDescription: String,
      inSchema: Json,
      outSchemaJson: Option[Json],
      isStructured: Option[Boolean]
  ):

    private def copy(
        toolName: String = this.toolName,
        toolDescription: String = this.toolDescription,
        inSchema: Json = this.inSchema,
        outSchemaJson: Option[Json] = this.outSchemaJson,
        isStructured: Option[Boolean] = this.isStructured
    ): ContextualIn[F, Ctx, A] =
      new ContextualIn[F, Ctx, A](toolName, toolDescription, inSchema, outSchemaJson, isStructured)

    def description(d: String): ContextualIn[F, Ctx, A] =
      copy(toolDescription = d)

    def out[R: Encoder.AsObject: JsonSchema]: ContextualIn[F, Ctx, A] =
      copy(outSchemaJson = Some(JsonSchema.toJson[R]), isStructured = Some(true))

    def run[R](handler: (Ctx, A) => F[R])(using Decoder[A], Encoder.AsObject[R], JsonSchema[R]): Tool[F, Ctx, A, R] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[R])
      new Tool[F, Ctx, A, R](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = (ctx, a, _) => handler(ctx, a),
        resultEncoder = r => ToolResult.structured(r.asJson)
      )(using summon[Decoder[A]])

    def runWithContext[R](handler: (Ctx, A, RequestContext[F]) => F[R])(using Decoder[A], Encoder.AsObject[R], JsonSchema[R]): Tool[F, Ctx, A, R] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[R])
      new Tool[F, Ctx, A, R](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = handler,
        resultEncoder = r => ToolResult.structured(r.asJson)
      )(using summon[Decoder[A]])

    def handle(handler: (Ctx, A) => F[ToolResult])(using Decoder[A]): Tool[F, Ctx, A, ToolResult] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[Unit])
      new Tool[F, Ctx, A, ToolResult](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = (ctx, a, _) => handler(ctx, a),
        resultEncoder = identity
      )(using summon[Decoder[A]])

    def handleWithContext(handler: (Ctx, A, RequestContext[F]) => F[ToolResult])(using Decoder[A]): Tool[F, Ctx, A, ToolResult] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[Unit])
      new Tool[F, Ctx, A, ToolResult](
        name = toolName,
        description = toolDescription,
        inputSchema = inSchema,
        outputSchema = finalOutput,
        handle = handler,
        resultEncoder = identity
      )(using summon[Decoder[A]])
