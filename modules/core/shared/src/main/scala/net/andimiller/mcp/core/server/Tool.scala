package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.schema.JsonSchema
import sourcecode.Name

/**
 * Tool builder and helper functions for creating tool handlers.
 */
object Tool:

  /**
   * Create a simple tool handler from a function with automatic schema derivation.
   *
   * Example:
   * {{{
   * case class AddRequest(a: Int, b: Int) derives JsonSchema, Decoder
   * case class AddResponse(sum: Int) derives Encoder
   *
   * val addTool = Tool.build { (req: AddRequest) =>
   *   IO.pure(AddResponse(req.a + req.b))
   * }(using Name("add"))
   * }}}
   */
  inline def build[F[_]: Async, A, R](
    handler: A => F[R]
  )(using
    toolName: Name,
    schema: JsonSchema[A],
    decoder: Decoder[A],
    encoder: Encoder.AsObject[R],
    outputSchemaInstance: JsonSchema[R]
  ): ToolHandler[F] =
    new ToolHandler[F]:
      def name: String = toolName.value
      def description: String = s"Tool: ${toolName.value}"
      def inputSchema: Json = JsonSchema.toJson[A]
      def outputSchema: Json = JsonSchema.toJson[R]
      def handle(arguments: Json): F[ToolResult] =
        arguments.as[A] match
          case Right(input) =>
            handler(input).map { result =>
              ToolResult.text(result.asJson.noSpaces)
            }
          case Left(error) =>
            Async[F].pure(ToolResult.error(s"Invalid arguments: ${error.getMessage}"))

  /**
   * Create a tool handler with explicit name and description.
   */
  inline def buildNamed[F[_]: Async, A, R](
    toolName: String,
    toolDescription: String
  )(
    handler: A => F[R]
  )(using
    schema: JsonSchema[A],
    decoder: Decoder[A],
    encoder: Encoder.AsObject[R],
    outputSchemaInstance: JsonSchema[R]
  ): ToolHandler[F] =
    new ToolHandler[F]:
      def name: String = toolName
      def description: String = toolDescription
      def inputSchema: Json = JsonSchema.toJson[A]
      def outputSchema: Json = JsonSchema.toJson[R]
      def handle(arguments: Json): F[ToolResult] =
        arguments.as[A] match
          case Right(input) =>
            handler(input).map { result =>
              ToolResult.text(result.asJson.noSpaces)
            }
          case Left(error) =>
            Async[F].pure(ToolResult.error(s"Invalid arguments: ${error.getMessage}"))

  /**
   * Create a tool that returns ToolResult directly (for more control over output).
   */
  inline def buildDirect[F[_]: Async, A](
    toolName: String,
    toolDescription: String,
    outputSchemaJson: Json
  )(
    handler: A => F[ToolResult]
  )(using
    schema: JsonSchema[A],
    decoder: Decoder[A]
  ): ToolHandler[F] =
    new ToolHandler[F]:
      def name: String = toolName
      def description: String = toolDescription
      def inputSchema: Json = JsonSchema.toJson[A]
      def outputSchema: Json = outputSchemaJson
      def handle(arguments: Json): F[ToolResult] =
        arguments.as[A] match
          case Right(input) =>
            handler(input)
          case Left(error) =>
            Async[F].pure(ToolResult.error(s"Invalid arguments: ${error.getMessage}"))

  /**
   * Fluent builder for tools (alternative to the inline helpers).
   */
  def builder[F[_]: Async]: ToolBuilder[F] = new ToolBuilder[F]

/**
 * Fluent builder for constructing tool handlers step by step.
 */
class ToolBuilder[F[_]: Async]:
  private var toolName: Option[String] = None
  private var toolDescription: Option[String] = None
  private var toolSchema: Option[Json] = None
  private var toolOutputSchema: Option[Json] = None

  def name(n: String): this.type =
    toolName = Some(n)
    this

  def description(d: String): this.type =
    toolDescription = Some(d)
    this

  def schema(s: Json): this.type =
    toolSchema = Some(s)
    this

  def schemaFrom[A](using schema: JsonSchema[A]): this.type =
    toolSchema = Some(JsonSchema.toJson[A])
    this

  def outputSchema(s: Json): this.type =
    toolOutputSchema = Some(s)
    this

  def outputSchemaFrom[R](using schema: JsonSchema[R]): this.type =
    toolOutputSchema = Some(JsonSchema.toJson[R])
    this

  def handler[A: Decoder, R: Encoder.AsObject: JsonSchema](f: A => F[R]): ToolHandler[F] =
    require(toolName.isDefined, "Tool name is required")
    require(toolSchema.isDefined, "Tool schema is required")

    val finalName = toolName.get
    val finalDescription = toolDescription.getOrElse(s"Tool: $finalName")
    val finalSchema = toolSchema.get
    val finalOutputSchema = toolOutputSchema.getOrElse(JsonSchema.toJson[R])

    new ToolHandler[F]:
      def name: String = finalName
      def description: String = finalDescription
      def inputSchema: Json = finalSchema
      def outputSchema: Json = finalOutputSchema
      def handle(arguments: Json): F[ToolResult] =
        arguments.as[A] match
          case Right(input) =>
            f(input).map { result =>
              ToolResult.text(result.asJson.noSpaces)
            }
          case Left(error) =>
            Async[F].pure(ToolResult.error(s"Invalid arguments: ${error.getMessage}"))

  def handlerDirect[A: Decoder](f: A => F[ToolResult]): ToolHandler[F] =
    require(toolName.isDefined, "Tool name is required")
    require(toolSchema.isDefined, "Tool schema is required")
    require(toolOutputSchema.isDefined, "Tool output schema is required")

    val finalName = toolName.get
    val finalDescription = toolDescription.getOrElse(s"Tool: $finalName")
    val finalSchema = toolSchema.get
    val finalOutputSchema = toolOutputSchema.get

    new ToolHandler[F]:
      def name: String = finalName
      def description: String = finalDescription
      def inputSchema: Json = finalSchema
      def outputSchema: Json = finalOutputSchema
      def handle(arguments: Json): F[ToolResult] =
        arguments.as[A] match
          case Right(input) =>
            f(input)
          case Left(error) =>
            Async[F].pure(ToolResult.error(s"Invalid arguments: ${error.getMessage}"))
