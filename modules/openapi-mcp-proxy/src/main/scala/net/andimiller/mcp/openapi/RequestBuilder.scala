package net.andimiller.mcp.openapi

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import net.andimiller.mcp.core.protocol.ToolResult
import net.andimiller.mcp.core.protocol.content.Content
import org.http4s.EntityDecoder
import org.http4s.Header
import org.http4s.Headers
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.circe.*
import org.http4s.client.Client

object RequestBuilder:

  def execute(
      client: Client[IO],
      baseUrl: String,
      method: Method,
      pathPattern: String,
      operation: ResolvedOperation,
      arguments: Json
  ): IO[ToolResult[Nothing]] =
    val args = arguments.asObject.getOrElse(io.circe.JsonObject.empty)

    val path = operation.pathParams.foldLeft(pathPattern) { (p, param) =>
      val value = args(param.name).flatMap(jsonToString).getOrElse("")
      p.replace(s"{${param.name}}", value)
    }

    val queryPairs = operation.queryParams.flatMap { param =>
      args(param.name).flatMap(jsonToQueryValue).map(v => param.name -> v)
    }

    val extraHeaders = operation.headerParams.flatMap { param =>
      args(param.name).flatMap(jsonToString).map { v =>
        Header.Raw(org.typelevel.ci.CIString(param.name), v)
      }
    }

    val bodyJson = if operation.hasBody then args("body") else None

    for
      baseUri <- IO.fromEither(
                   Uri
                     .fromString(baseUrl.stripSuffix("/") + "/" + path.stripPrefix("/"))
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
                        case Right(json) =>
                          val wrapped = wrapIfArray(json)
                          ToolResult.Raw(
                            content = List(Content.Text(wrapped.noSpaces)),
                            structuredContent = Some(wrapped),
                            isError = false
                          )
                        case Left(_) => ToolResult.Text(body)
                    else ToolResult.Error(s"HTTP ${response.status.code}: $body")
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

  private def wrapIfArray(json: Json): Json =
    if json.isArray then Json.obj("items" -> json)
    else json
