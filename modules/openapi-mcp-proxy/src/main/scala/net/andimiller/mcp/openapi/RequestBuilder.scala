package net.andimiller.mcp.openapi

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{EntityDecoder, Header, Headers, Method, Request, Uri}
import org.http4s.circe.*
import org.http4s.client.Client
import net.andimiller.mcp.core.protocol.ToolResult
import sttp.apispec.openapi.{Operation, ParameterIn}

import scala.collection.immutable.ListMap

object RequestBuilder:

  /** Information about a resolved operation needed for request construction. */
  case class ResolvedOperation(
      pathParams: List[ResolvedParam],
      queryParams: List[ResolvedParam],
      headerParams: List[ResolvedParam],
      hasBody: Boolean
  )

  case class ResolvedParam(name: String, required: Boolean)

  /** Execute an HTTP request for a tool call. */
  def execute(
      client: Client[IO],
      baseUrl: String,
      method: Method,
      pathPattern: String,
      operation: ResolvedOperation,
      arguments: Json
  ): IO[ToolResult] =
    val args = arguments.asObject.getOrElse(io.circe.JsonObject.empty)

    // 1. Substitute path parameters
    val path = operation.pathParams.foldLeft(pathPattern) { (p, param) =>
      val value = args(param.name).flatMap(jsonToString).getOrElse("")
      p.replace(s"{${param.name}}", value)
    }

    // 2. Build query string
    val queryPairs = operation.queryParams.flatMap { param =>
      args(param.name).flatMap(jsonToQueryValue).map(v => param.name -> v)
    }

    // 3. Build headers
    val extraHeaders = operation.headerParams.flatMap { param =>
      args(param.name).flatMap(jsonToString).map { v =>
        Header.Raw(org.typelevel.ci.CIString(param.name), v)
      }
    }

    // 4. Request body
    val bodyJson = if operation.hasBody then args("body") else None

    for
      baseUri <- IO.fromEither(
        Uri.fromString(baseUrl.stripSuffix("/") + "/" + path.stripPrefix("/"))
          .leftMap(e => new Exception(s"Invalid URI: ${e.message}"))
      )
      uri = queryPairs.foldLeft(baseUri) { (u, kv) =>
        u.withQueryParam(kv._1, kv._2)
      }
      request = {
        val base = Request[IO](method = method, uri = uri)
          .withHeaders(Headers(extraHeaders))
        bodyJson match
          case Some(json) => base.withEntity(json)(jsonEncoderOf[IO, Json])
          case None       => base
      }
      result <- client.run(request).use { response =>
        EntityDecoder.decodeText(response).map { body =>
          if response.status.isSuccess then
            io.circe.parser.parse(body) match
              case Right(json) => ToolResult.structured(wrapIfArray(json))
              case Left(_)     => ToolResult.text(body)
          else ToolResult.error(s"HTTP ${response.status.code}: $body")
        }
      }
    yield result

  private def jsonToString(json: Json): Option[String] =
    json.asString
      .orElse(json.asNumber.map(_.toString))
      .orElse(json.asBoolean.map(_.toString))
      .orElse(if json.isNull then None else Some(json.noSpaces))

  private def jsonToQueryValue(json: Json): Option[String] =
    jsonToString(json)

  /** Wrap array responses in an object to match the schema wrapping done by SchemaConverter.wrapIfArray. */
  private def wrapIfArray(json: Json): Json =
    if json.isArray then Json.obj("items" -> json)
    else json
