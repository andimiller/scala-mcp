package net.andimiller.mcp.openapi

import cats.effect.IO

import net.andimiller.mcp.core.server.Tool
import net.andimiller.mcp.core.server.Tool.toTool

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
  ): List[Tool[IO, Unit]] =
    val operations = OpenApiOperation.build(spec, operationIds)
    operations.map { op =>
      val method = Method.fromString(op.method).getOrElse(Method.GET)
      op.definition.toTool[IO, Unit](args =>
        RequestBuilder.execute(client, baseUrl, method, op.pathPattern, op.resolvedOperation, args)
      )
    }
