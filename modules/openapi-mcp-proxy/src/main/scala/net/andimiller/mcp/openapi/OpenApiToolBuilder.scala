package net.andimiller.mcp.openapi

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.ToolResult
import net.andimiller.mcp.core.server.ToolHandler
import org.http4s.Method
import org.http4s.client.Client
import sttp.apispec.{Schema, SchemaLike, SchemaType, AnySchema}
import sttp.apispec.openapi.{OpenAPI, Operation, Parameter, ParameterIn, PathItem, RequestBody, Response, ResponsesCodeKey, ResponsesDefaultKey}
import sttp.apispec.openapi.circe.given

import scala.collection.immutable.ListMap

object OpenApiToolBuilder:

  /** HTTP methods to check on each PathItem, in order. */
  private val Methods: List[(PathItem => Option[Operation], Method)] = List(
    (_.get, Method.GET),
    (_.post, Method.POST),
    (_.put, Method.PUT),
    (_.delete, Method.DELETE),
    (_.patch, Method.PATCH)
  )

  /** List all operation IDs in the spec with their HTTP method and path. */
  def listOperationIds(spec: OpenAPI): List[(String, String, String)] =
    spec.paths.pathItems.toList.flatMap { case (pathPattern, pathItem) =>
      Methods.flatMap { case (getter, method) =>
        getter(pathItem).flatMap { op =>
          op.operationId.map(id => (id, method.name, pathPattern))
        }
      }
    }

  /** Build ToolHandlers for the specified operationIds from the OpenAPI spec. */
  def buildTools(
      spec: OpenAPI,
      operationIds: List[String],
      client: Client[IO],
      baseUrl: String
  ): List[ToolHandler[IO]] =
    val components = spec.components.getOrElse(sttp.apispec.openapi.Components())
    val schemas = components.schemas

    // Collect all operations with their path and method
    val allOperations: List[(String, Method, Operation, String)] =
      spec.paths.pathItems.toList.flatMap { case (pathPattern, pathItem) =>
        Methods.flatMap { case (getter, method) =>
          getter(pathItem).flatMap { op =>
            op.operationId.map(id => (pathPattern, method, op, id))
          }
        }
      }

    val operationIdSet = operationIds.toSet
    val found = allOperations.filter(t => operationIdSet.contains(t._4))

    found.map { case (pathPattern, method, operation, operationId) =>
      buildToolHandler(operationId, pathPattern, method, operation, components, schemas, client, baseUrl)
    }

  private def buildToolHandler(
      operationId: String,
      pathPattern: String,
      method: Method,
      operation: Operation,
      components: sttp.apispec.openapi.Components,
      schemas: ListMap[String, SchemaLike],
      client: Client[IO],
      baseUrl: String
  ): ToolHandler[IO] =
    // Resolve parameters
    val params = operation.parameters.flatMap(SchemaConverter.resolveParameter(_, components.parameters))

    val pathParams = params.filter(_.in == ParameterIn.Path)
    val queryParams = params.filter(_.in == ParameterIn.Query)
    val headerParams = params.filter(_.in == ParameterIn.Header)

    // Build input schema
    val paramProperties: ListMap[String, Json] = ListMap.from(
      (pathParams ++ queryParams ++ headerParams).map { p =>
        val paramSchema = p.schema match
          case Some(sl) => SchemaConverter.schemaToJson(sl, schemas)
          case None     => Json.obj("type" -> Json.fromString("string"))
        p.name -> paramSchema
      }
    )

    val requiredParams: List[String] =
      pathParams.map(_.name) ++
        (queryParams ++ headerParams).filter(_.required.getOrElse(false)).map(_.name)

    // Request body → "body" property
    val resolvedBody = operation.requestBody.flatMap(SchemaConverter.resolveRequestBody(_, components.requestBodies))
    val bodySchema: Option[Json] = resolvedBody.flatMap { rb =>
      rb.content.get("application/json").flatMap(_.schema).map { sl =>
        SchemaConverter.schemaToJson(sl, schemas)
      }
    }
    val bodyRequired = resolvedBody.exists(_.required.getOrElse(false))

    val allProperties = bodySchema match
      case Some(bs) => paramProperties + ("body" -> bs)
      case None     => paramProperties

    val allRequired = if bodyRequired then requiredParams :+ "body" else requiredParams

    val input = Json.obj(
      "type" -> Json.fromString("object"),
      "properties" -> Json.fromFields(allProperties.toList),
      "required" -> Json.arr(allRequired.map(Json.fromString)*)
    )

    // Build output schema
    val output = buildOutputSchema(operation, components, schemas)

    val resolvedOp = RequestBuilder.ResolvedOperation(
      pathParams = pathParams.map(p => RequestBuilder.ResolvedParam(p.name, p.required.getOrElse(true))),
      queryParams = queryParams.map(p => RequestBuilder.ResolvedParam(p.name, p.required.getOrElse(false))),
      headerParams = headerParams.map(p => RequestBuilder.ResolvedParam(p.name, p.required.getOrElse(false))),
      hasBody = bodySchema.isDefined
    )

    val desc = operation.summary
      .orElse(operation.description)
      .getOrElse(operationId)

    new ToolHandler[IO]:
      def name: String = operationId
      def description: String = desc
      def inputSchema: Json = input
      def outputSchema: Json = output
      def handle(arguments: Json): IO[ToolResult] =
        RequestBuilder.execute(client, baseUrl, method, pathPattern, resolvedOp, arguments)

  private def buildOutputSchema(
      operation: Operation,
      components: sttp.apispec.openapi.Components,
      schemas: ListMap[String, SchemaLike]
  ): Json =
    val responses = operation.responses.responses
    // Try 200, 201, then default
    val responseOpt =
      responses.get(ResponsesCodeKey(200))
        .orElse(responses.get(ResponsesCodeKey(201)))
        .orElse(responses.get(ResponsesDefaultKey))

    val schemaJson = responseOpt
      .flatMap(SchemaConverter.resolveResponse(_, components.responses))
      .flatMap(_.content.get("application/json"))
      .flatMap(_.schema)
      .map(sl => SchemaConverter.schemaToJson(sl, schemas))
      .getOrElse(Json.obj("type" -> Json.fromString("object")))

    SchemaConverter.wrapIfArray(schemaJson)
