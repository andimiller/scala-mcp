package net.andimiller.mcp.explorer

import cats.syntax.all.*
import io.circe.{Decoder as CirceDecoder, *}
import io.circe.syntax.*
import io.circe.parser.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.{Message as JsonRpcMessage, *}
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import tyrian.http.*

object McpClient:

  private def nextId(): String = scala.util.Random.alphanumeric.take(8).mkString

  def parseResp[A: CirceDecoder](text: String): Either[String, A] =
    for
      json   <- parse(text).leftMap(_.getMessage)
      msg    <- json.as[JsonRpcMessage].leftMap(_.getMessage)
      result <- msg match
                  case JsonRpcMessage.Response(_, _, Some(r), _) => r.as[A].leftMap(_.getMessage)
                  case JsonRpcMessage.Response(_, _, _, Some(e)) => Left(s"Server error ${e.code}: ${e.message}")
                  case _                                         => Left("Unexpected message type")
    yield result

  def schemaToTemplate(schema: Json): String =
    val properties = schema.hcursor.downField("properties")
    if properties.succeeded then
      val ps = properties.keys.getOrElse(Nil).map { k =>
        val pt = properties.downField(k).get[String]("type").toOption
        val dv = pt match
          case Some("string")             => Json.fromString("")
          case Some("integer" | "number") => Json.fromInt(0)
          case Some("boolean")            => Json.False
          case Some("array")              => Json.arr()
          case Some("object")             => Json.obj()
          case _                          => Json.Null
        (k, dv)
      }
      Json.fromFields(ps).spaces2
    else "{}"

  private def jsonRpcBody(method: String, params: Json): Body =
    Body.json(
      Json
        .obj("jsonrpc" -> "2.0".asJson, "id" -> nextId().asJson, "method" -> method.asJson, "params" -> params)
        .noSpaces
    )

  private def notifBody(method: String, params: Json): Body =
    Body.json(Json.obj("jsonrpc" -> "2.0".asJson, "method" -> method.asJson, "params" -> params).noSpaces)

  def initializeRequest(url: String): Request[String] =
    Request.post(
      url,
      Body.json(
        Json
          .obj(
            "jsonrpc" -> "2.0".asJson,
            "id"      -> nextId().asJson,
            "method"  -> "initialize".asJson,
            "params"  -> Json.obj(
              "protocolVersion" -> "2025-11-25".asJson,
              "capabilities"    -> Json.obj(),
              "clientInfo"      -> Json.obj("name" -> "mcp-explorer".asJson, "version" -> "1.0.0".asJson)
            )
          )
          .noSpaces
      )
    )

  def notifRequest(url: String, method: String, params: Json, sid: String): Request[String] =
    Request
      .post(url, notifBody(method, params))
      .withHeaders(Header("Mcp-Session-Id", sid))

  def listToolsRequest(url: String, sid: String): Request[String] =
    Request
      .post(url, jsonRpcBody("tools/list", Json.obj()))
      .withHeaders(Header("Mcp-Session-Id", sid))

  def listResourcesRequest(url: String, sid: String): Request[String] =
    Request
      .post(url, jsonRpcBody("resources/list", Json.obj()))
      .withHeaders(Header("Mcp-Session-Id", sid))

  def listTemplatesRequest(url: String, sid: String): Request[String] =
    Request
      .post(url, jsonRpcBody("resources/templates/list", Json.obj()))
      .withHeaders(Header("Mcp-Session-Id", sid))

  def readResourceRequest(url: String, uri: String, sid: String): Request[String] =
    Request
      .post(url, jsonRpcBody("resources/read", Json.obj("uri" -> uri.asJson)))
      .withHeaders(Header("Mcp-Session-Id", sid))

  def listPromptsRequest(url: String, sid: String): Request[String] =
    Request
      .post(url, jsonRpcBody("prompts/list", Json.obj()))
      .withHeaders(Header("Mcp-Session-Id", sid))

  def getPromptRequest(url: String, name: String, args: Map[String, String], sid: String): Request[String] =
    Request
      .post(url, jsonRpcBody("prompts/get", Json.obj("name" -> name.asJson, "arguments" -> args.asJson)))
      .withHeaders(Header("Mcp-Session-Id", sid))

  def callToolRequest(url: String, name: String, args: Json, sid: String): Request[String] =
    Request
      .post(url, jsonRpcBody("tools/call", Json.obj("name" -> name.asJson, "arguments" -> args)))
      .withHeaders(Header("Mcp-Session-Id", sid))
