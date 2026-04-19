package net.andimiller.mcp.openapi

import cats.effect.IO
import io.circe.Json
import net.andimiller.mcp.core.protocol.ToolResult
import net.andimiller.mcp.core.server.Tool
import org.http4s.Method
import org.http4s.client.Client
import sttp.apispec.openapi.OpenAPI

object OpenApiToolBuilder:

  def listOperationIds(spec: OpenAPI): List[(String, String, String)] =
    OpenApiOperation.listOperationIds(spec)

  def buildTools(
      spec: OpenAPI,
      operationIds: List[String],
      client: Client[IO],
      baseUrl: String
  ): List[Tool.Resolved[IO]] =
    val operations = OpenApiOperation.build(spec, operationIds)
    operations.map { op =>
      val method = Method.fromString(op.method).getOrElse(Method.GET)
      new Tool.Resolved[IO]:
        def name: String = op.definition.name
        def description: String = op.definition.description
        def inputSchema: Json = op.definition.inputSchema
        def outputSchema: Json = op.definition.outputSchema
        def handle(arguments: Json): IO[ToolResult] =
          RequestBuilder.execute(client, baseUrl, method, op.pathPattern, op.resolvedOperation, arguments)
    }