package net.andimiller.mcp.openapi

import io.circe.Json
import io.circe.syntax.*
import sttp.apispec.{Schema, SchemaLike, AnySchema, SchemaType}
import sttp.apispec.openapi.{Reference, ReferenceOr}
import sttp.apispec.openapi.circe.given

import scala.collection.immutable.ListMap

object SchemaConverter:

  private val MaxRefDepth = 20

  /** Resolve a SchemaLike, following $ref if present, against component schemas. */
  def resolveSchemaLike(
      schemaLike: SchemaLike,
      components: ListMap[String, SchemaLike],
      depth: Int = 0
  ): SchemaLike =
    if depth > MaxRefDepth then schemaLike
    else
      schemaLike match
        case s: Schema if s.$ref.isDefined =>
          val refName = s.$ref.get.stripPrefix("#/components/schemas/")
          components.get(refName) match
            case Some(resolved) => resolveSchemaLike(resolved, components, depth + 1)
            case None           => s
        case other => other

  /** Resolve a ReferenceOr[T] where T is Parameter. */
  def resolveParameter(
      ref: ReferenceOr[sttp.apispec.openapi.Parameter],
      components: ListMap[String, ReferenceOr[sttp.apispec.openapi.Parameter]]
  ): Option[sttp.apispec.openapi.Parameter] =
    ref match
      case Right(param) => Some(param)
      case Left(reference) =>
        val name = reference.$ref.stripPrefix("#/components/parameters/")
        components.get(name).flatMap:
          case Right(p) => Some(p)
          case Left(_)  => None

  /** Resolve a ReferenceOr[RequestBody]. */
  def resolveRequestBody(
      ref: ReferenceOr[sttp.apispec.openapi.RequestBody],
      components: ListMap[String, ReferenceOr[sttp.apispec.openapi.RequestBody]]
  ): Option[sttp.apispec.openapi.RequestBody] =
    ref match
      case Right(body) => Some(body)
      case Left(reference) =>
        val name = reference.$ref.stripPrefix("#/components/requestBodies/")
        components.get(name).flatMap:
          case Right(b) => Some(b)
          case Left(_)  => None

  /** Resolve a ReferenceOr[Response]. */
  def resolveResponse(
      ref: ReferenceOr[sttp.apispec.openapi.Response],
      components: ListMap[String, ReferenceOr[sttp.apispec.openapi.Response]]
  ): Option[sttp.apispec.openapi.Response] =
    ref match
      case Right(resp) => Some(resp)
      case Left(reference) =>
        val name = reference.$ref.stripPrefix("#/components/responses/")
        components.get(name).flatMap:
          case Right(r) => Some(r)
          case Left(_)  => None

  /** Convert a SchemaLike to JSON, resolving all $ref within it recursively. */
  def schemaToJson(
      schemaLike: SchemaLike,
      components: ListMap[String, SchemaLike]
  ): Json =
    val resolved = resolveSchemaLike(schemaLike, components)
    val json = resolved.asJson.deepDropNullValues
    resolveRefsInJson(json, components)

  /** Walk a JSON tree and inline any {"$ref": "#/components/schemas/..."} objects. */
  private def resolveRefsInJson(
      json: Json,
      components: ListMap[String, SchemaLike],
      depth: Int = 0
  ): Json =
    if depth > MaxRefDepth then json
    else json.foldWith(new Json.Folder[Json]:
      def onNull: Json = Json.Null
      def onBoolean(value: Boolean): Json = Json.fromBoolean(value)
      def onNumber(value: io.circe.JsonNumber): Json = Json.fromJsonNumber(value)
      def onString(value: String): Json = Json.fromString(value)
      def onArray(value: Vector[Json]): Json =
        Json.fromValues(value.map(resolveRefsInJson(_, components, depth)))
      def onObject(value: io.circe.JsonObject): Json =
        value("$ref").flatMap(_.asString) match
          case Some(ref) if ref.startsWith("#/components/schemas/") =>
            val name = ref.stripPrefix("#/components/schemas/")
            components.get(name) match
              case Some(schema) =>
                val inlined = resolveSchemaLike(schema, components, depth).asJson.deepDropNullValues
                resolveRefsInJson(inlined, components, depth + 1)
              case None => Json.fromJsonObject(value)
          case _ =>
            Json.fromJsonObject(
              io.circe.JsonObject.fromIterable(
                value.toIterable.map((k, v) => k -> resolveRefsInJson(v, components, depth))
              )
            )
    )

  /** Wrap an array-type schema in an object (Claude Code rejects array outputSchema). */
  def wrapIfArray(schema: Json): Json =
    val isArray = schema.hcursor.downField("type").as[String].toOption.contains("array")
    if isArray then
      Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "items" -> schema
        ),
        "required" -> Json.arr(Json.fromString("items"))
      )
    else schema
