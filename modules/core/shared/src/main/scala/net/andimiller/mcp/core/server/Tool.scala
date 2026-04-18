package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.schema.JsonSchema

object Tool:

  def builder[F[_]: Async]: ToolBuilder.Empty[F] = new ToolBuilder.Empty[F]

final class ToolBuilder[F[_]: Async] private (
  val toolName: String,
  val toolDescription: String,
  val inputSchemaJson: Option[Json],
  val outputSchemaJson: Option[Json]
):

  private def copy(
    toolName: String = this.toolName,
    toolDescription: String = this.toolDescription,
    inputSchemaJson: Option[Json] = this.inputSchemaJson,
    outputSchemaJson: Option[Json] = this.outputSchemaJson
  ): ToolBuilder[F] =
    new ToolBuilder[F](toolName, toolDescription, inputSchemaJson, outputSchemaJson)

  def description(d: String): ToolBuilder[F] =
    copy(toolDescription = d)

  def inputSchema(json: Json): ToolBuilder[F] =
    copy(inputSchemaJson = Some(json))

  def outputSchema(json: Json): ToolBuilder[F] =
    copy(outputSchemaJson = Some(json))

  def in[A](using JsonSchema[A], Decoder[A]): ToolBuilder.In[F, A] =
    ToolBuilder.In(toolName, toolDescription, JsonSchema.toJson[A], outputSchemaJson)

  def logic[A](handler: A => F[ToolResult])(using JsonSchema[A], Decoder[A]): ToolHandler[F] =
    val finalOutput = outputSchemaJson.getOrElse(Json.obj())
    new ToolHandler[F]:
      def name: String = toolName
      def description: String = toolDescription
      def inputSchema: Json = JsonSchema.toJson[A]
      def outputSchema: Json = finalOutput
      def handle(arguments: Json): F[ToolResult] =
        arguments.as[A] match
          case Right(input)  => handler(input)
          case Left(error)   => Async[F].pure(ToolResult.error(s"Invalid arguments: ${error.getMessage}"))

object ToolBuilder:

  final class Empty[F[_]: Async]:
    def name(n: String): ToolBuilder[F] = new ToolBuilder[F](n, s"Tool: $n", None, None)

  final class In[F[_]: Async, A] private[ToolBuilder] (
    val toolName: String,
    val toolDescription: String,
    val inSchema: Json,
    val outSchemaJson: Option[Json]
  ):

    private def copy(
      toolName: String = this.toolName,
      toolDescription: String = this.toolDescription,
      inSchema: Json = this.inSchema,
      outSchemaJson: Option[Json] = this.outSchemaJson
    ): In[F, A] =
      new In(toolName, toolDescription, inSchema, outSchemaJson)

    def description(d: String): In[F, A] =
      copy(toolDescription = d)

    def out[R: Encoder.AsObject: JsonSchema]: In[F, A] =
      copy(outSchemaJson = Some(JsonSchema.toJson[R]))

    def run[R](handler: A => F[R])(using Decoder[A], Encoder.AsObject[R], JsonSchema[R]): ToolHandler[F] =
      val finalOutput = outSchemaJson.getOrElse(JsonSchema.toJson[R])
      new ToolHandler[F]:
        def name: String = toolName
        def description: String = toolDescription
        def inputSchema: Json = inSchema
        def outputSchema: Json = finalOutput
        def handle(arguments: Json): F[ToolResult] =
          arguments.as[A] match
            case Right(input) =>
              handler(input).map { result =>
                ToolResult.structured(result.asJson)
              }
            case Left(error) =>
              Async[F].pure(ToolResult.error(s"Invalid arguments: ${error.getMessage}"))

    def handle(handler: A => F[ToolResult])(using Decoder[A]): ToolHandler[F] =
      val finalOutput = outSchemaJson.getOrElse(Json.obj())
      new ToolHandler[F]:
        def name: String = toolName
        def description: String = toolDescription
        def inputSchema: Json = inSchema
        def outputSchema: Json = finalOutput
        def handle(arguments: Json): F[ToolResult] =
          arguments.as[A] match
            case Right(input)  => handler(input)
            case Left(error)  => Async[F].pure(ToolResult.error(s"Invalid arguments: ${error.getMessage}"))