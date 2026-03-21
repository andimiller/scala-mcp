package net.andimiller.mcp.core.codecs

import io.circe.*
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.*
import net.andimiller.mcp.core.protocol.content.*

/**
 * Custom Circe codecs for MCP protocol types that need special encoding.
 *
 * Most protocol types use `derives Encoder.AsObject, Decoder` directly on the case classes.
 * This object only contains codecs for types that need custom handling:
 * - RequestId (opaque type for String | Long union)
 * - JsonRpcError
 * - Message enum (discriminated union)
 * - Content enum (type discriminator)
 * - PromptRole enum
 */
object CirceCodecs:

  // RequestId codecs (String | Long union type)
  given Encoder[RequestId] = Encoder.instance { id =>
    id.value match
      case s: String => s.asJson
      case l: Long => l.asJson
  }

  given Decoder[RequestId] = Decoder.instance { cursor =>
    cursor.as[String].map(RequestId.fromString)
      .orElse(cursor.as[Long].map(RequestId.fromLong))
  }

  // JsonRpcError
  given Encoder[JsonRpcError] with
    def apply(error: JsonRpcError): Json = Json.obj(
      "code" -> error.code.asJson,
      "message" -> error.message.asJson,
      "data" -> error.data.asJson
    )

  given Decoder[JsonRpcError] = Decoder.instance { cursor =>
    for
      code <- cursor.get[Int]("code")
      message <- cursor.get[String]("message")
      data <- cursor.get[Option[Json]]("data")
    yield JsonRpcError(code, message, data)
  }

  // Message enum with discriminated union based on field presence
  given Encoder[Message] = Encoder.instance {
    case Message.Request(jsonrpc, id, method, params) =>
      Json.obj(
        "jsonrpc" -> jsonrpc.asJson,
        "id" -> id.asJson,
        "method" -> method.asJson,
        "params" -> params.asJson
      )

    case Message.Response(jsonrpc, id, result, error) =>
      Json.obj(
        "jsonrpc" -> jsonrpc.asJson,
        "id" -> id.asJson,
        "result" -> result.asJson,
        "error" -> error.asJson
      ).deepDropNullValues

    case Message.Notification(jsonrpc, method, params) =>
      Json.obj(
        "jsonrpc" -> jsonrpc.asJson,
        "method" -> method.asJson,
        "params" -> params.asJson
      )
  }

  given Decoder[Message] = Decoder.instance { cursor =>
    val hasId = cursor.downField("id").succeeded
    val hasResult = cursor.downField("result").succeeded
    val hasError = cursor.downField("error").succeeded

    if hasId && (hasResult || hasError) then
      // Response
      for
        jsonrpc <- cursor.get[String]("jsonrpc")
        id <- cursor.get[RequestId]("id")
        result <- cursor.get[Option[Json]]("result")
        error <- cursor.get[Option[JsonRpcError]]("error")
      yield Message.Response(jsonrpc, id, result, error)
    else if hasId then
      // Request
      for
        jsonrpc <- cursor.get[String]("jsonrpc")
        id <- cursor.get[RequestId]("id")
        method <- cursor.get[String]("method")
        params <- cursor.get[Option[Json]]("params")
      yield Message.Request(jsonrpc, id, method, params)
    else
      // Notification
      for
        jsonrpc <- cursor.get[String]("jsonrpc")
        method <- cursor.get[String]("method")
        params <- cursor.get[Option[Json]]("params")
      yield Message.Notification(jsonrpc, method, params)
  }

  // Content enum with explicit type discriminator
  given Encoder[Content] = Encoder.instance {
    case Content.Text(text) =>
      Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson
      )

    case Content.Image(data, mimeType) =>
      Json.obj(
        "type" -> "image".asJson,
        "data" -> data.asJson,
        "mimeType" -> mimeType.asJson
      )

    case Content.Audio(data, mimeType) =>
      Json.obj(
        "type" -> "audio".asJson,
        "data" -> data.asJson,
        "mimeType" -> mimeType.asJson
      )

    case Content.Resource(uri, mimeType, text) =>
      Json.obj(
        "type" -> "resource".asJson,
        "uri" -> uri.asJson,
        "mimeType" -> mimeType.asJson,
        "text" -> text.asJson
      )
  }

  given Decoder[Content] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "text" =>
        cursor.get[String]("text").map(Content.Text.apply)

      case "image" =>
        for
          data <- cursor.get[String]("data")
          mimeType <- cursor.get[String]("mimeType")
        yield Content.Image(data, mimeType)

      case "audio" =>
        for
          data <- cursor.get[String]("data")
          mimeType <- cursor.get[String]("mimeType")
        yield Content.Audio(data, mimeType)

      case "resource" =>
        for
          uri <- cursor.get[String]("uri")
          mimeType <- cursor.get[Option[String]]("mimeType")
          text <- cursor.get[Option[String]]("text")
        yield Content.Resource(uri, mimeType, text)

      case other =>
        Left(DecodingFailure(s"Unknown content type: $other", cursor.history))
    }
  }

  // PromptRole enum
  given Encoder[PromptRole] = Encoder.instance {
    case PromptRole.User => "user".asJson
    case PromptRole.Assistant => "assistant".asJson
  }

  given Decoder[PromptRole] = Decoder.instance { cursor =>
    cursor.as[String].flatMap {
      case "user" => Right(PromptRole.User)
      case "assistant" => Right(PromptRole.Assistant)
      case other => Left(DecodingFailure(s"Unknown role: $other", cursor.history))
    }
  }
