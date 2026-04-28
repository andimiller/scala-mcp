package net.andimiller.mcp.openapi

import scala.collection.immutable.ListMap

import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.ToolDefinition

import io.circe.Json
import sttp.apispec.ExampleValue
import sttp.apispec.SchemaLike
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.Operation
import sttp.apispec.openapi.Parameter
import sttp.apispec.openapi.ParameterIn
import sttp.apispec.openapi.PathItem
import sttp.apispec.openapi.ResponsesCodeKey
import sttp.apispec.openapi.ResponsesDefaultKey

case class ResolvedParam(name: String, required: Boolean)

case class ResolvedOperation(
    pathParams: List[ResolvedParam],
    queryParams: List[ResolvedParam],
    headerParams: List[ResolvedParam],
    hasBody: Boolean
)

case class OpenApiOperation(
    definition: ToolDefinition,
    pathPattern: String,
    method: String,
    resolvedOperation: ResolvedOperation
)

object OpenApiOperation:

  private val Methods: List[(PathItem => Option[Operation], String)] = List(
    (_.get, "GET"),
    (_.post, "POST"),
    (_.put, "PUT"),
    (_.delete, "DELETE"),
    (_.patch, "PATCH")
  )

  def listOperationIds(spec: OpenAPI): List[(String, String, String)] =
    spec.paths.pathItems.toList.flatMap { case (pathPattern, pathItem) =>
      Methods.flatMap { case (getter, method) =>
        getter(pathItem).flatMap { op =>
          op.operationId.map(id => (id, method, pathPattern))
        }
      }
    }

  def build(spec: OpenAPI, operationIds: List[String]): List[OpenApiOperation] =
    val components = spec.components.getOrElse(sttp.apispec.openapi.Components())
    val schemas    = components.schemas

    val allOperations: List[(String, String, Operation, String)] =
      spec.paths.pathItems.toList.flatMap { case (pathPattern, pathItem) =>
        Methods.flatMap { case (getter, method) =>
          getter(pathItem).flatMap { op =>
            op.operationId.map(id => (pathPattern, method, op, id))
          }
        }
      }

    val operationIdSet = operationIds.toSet
    val found          = allOperations.filter(t => operationIdSet.contains(t._4))

    found.map { case (pathPattern, method, operation, operationId) =>
      buildOperation(operationId, pathPattern, method, operation, components, schemas)
    }

  private def buildOperation(
      operationId: String,
      pathPattern: String,
      method: String,
      operation: Operation,
      components: sttp.apispec.openapi.Components,
      schemas: ListMap[String, SchemaLike]
  ): OpenApiOperation =
    val params = operation.parameters.flatMap(SchemaConverter.resolveParameter(_, components.parameters))

    val pathParams   = params.filter(_.in.value === ParameterIn.Path.value)
    val queryParams  = params.filter(_.in.value === ParameterIn.Query.value)
    val headerParams = params.filter(_.in.value === ParameterIn.Header.value)

    val paramProperties: ListMap[String, Json] = ListMap.from(
      (pathParams ++ queryParams ++ headerParams).map { p =>
        val baseSchema = p.schema match
          case Some(sl) => SchemaConverter.schemaToJson(sl, schemas)
          case None     => Json.obj("type" -> Json.fromString("string"))
        p.name -> enrichSchemaWithParamMetadata(baseSchema, p)
      }
    )

    val requiredParams: List[String] =
      pathParams.map(_.name) ++
        (queryParams ++ headerParams).filter(_.required.getOrElse(false)).map(_.name)

    val resolvedBody             = operation.requestBody.flatMap(SchemaConverter.resolveRequestBody(_, components.requestBodies))
    val bodySchema: Option[Json] = resolvedBody.flatMap { rb =>
      rb.content.get("application/json").flatMap(_.schema).map { sl =>
        val schemaJson = SchemaConverter.schemaToJson(sl, schemas)
        rb.description match
          case Some(desc) => enrichSchemaWithDescription(schemaJson, desc)
          case None       => schemaJson
      }
    }
    val bodyRequired = resolvedBody.exists(_.required.getOrElse(false))

    val allProperties = bodySchema match
      case Some(bs) => paramProperties + ("body" -> bs)
      case None     => paramProperties

    val allRequired = if bodyRequired then requiredParams :+ "body" else requiredParams

    val input = Json.obj(
      "type"       -> Json.fromString("object"),
      "properties" -> Json.fromFields(allProperties.toList),
      "required"   -> Json.arr(allRequired.map(Json.fromString)*)
    )

    val output = buildOutputSchema(operation, components, schemas)

    val resolvedOp = ResolvedOperation(
      pathParams = pathParams.map(p => ResolvedParam(p.name, p.required.getOrElse(true))),
      queryParams = queryParams.map(p => ResolvedParam(p.name, p.required.getOrElse(false))),
      headerParams = headerParams.map(p => ResolvedParam(p.name, p.required.getOrElse(false))),
      hasBody = bodySchema.isDefined
    )

    val desc = operation.summary
      .orElse(operation.description)
      .getOrElse(operationId)

    OpenApiOperation(
      definition = ToolDefinition(operationId, desc, input, Some(output)),
      pathPattern = pathPattern,
      method = method,
      resolvedOperation = resolvedOp
    )

  private def buildOutputSchema(
      operation: Operation,
      components: sttp.apispec.openapi.Components,
      schemas: ListMap[String, SchemaLike]
  ): Json =
    val responses   = operation.responses.responses
    val responseOpt =
      responses
        .get(ResponsesCodeKey(200))
        .orElse(responses.get(ResponsesCodeKey(201)))
        .orElse(responses.get(ResponsesDefaultKey))

    val schemaJson = responseOpt
      .flatMap(SchemaConverter.resolveResponse(_, components.responses))
      .flatMap(_.content.get("application/json"))
      .flatMap(_.schema)
      .map(sl => SchemaConverter.schemaToJson(sl, schemas))
      .getOrElse(Json.obj("type" -> Json.fromString("object")))

    SchemaConverter.wrapIfArray(schemaJson)

  private def enrichSchemaWithParamMetadata(schema: Json, p: Parameter): Json =
    val withDesc = p.description match
      case Some(desc) => enrichSchemaWithDescription(schema, desc)
      case None       => schema
    p.example match
      case Some(ev) => enrichSchemaWithExample(withDesc, ev)
      case None     => withDesc

  private def enrichSchemaWithDescription(schema: Json, description: String): Json =
    schema.asObject match
      case Some(obj) => Json.fromJsonObject(obj.add("description", Json.fromString(description)))
      case None      => schema

  private def enrichSchemaWithExample(schema: Json, example: ExampleValue): Json =
    schema.asObject match
      case Some(obj) => Json.fromJsonObject(obj.add("example", SchemaConverter.exampleValueToJson(example)))
      case None      => schema
