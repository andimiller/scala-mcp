package net.andimiller.mcp.core.codecs

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.*
import net.andimiller.mcp.core.protocol.jsonrpc.*

import io.circe.*
import io.circe.syntax.*

/** Custom Circe codecs for MCP protocol types that need special encoding.
  *
  * Most protocol types use `derives Encoder.AsObject, Decoder` directly on the case classes. This object only contains
  * codecs for types that need custom handling:
  *   - RequestId (opaque type for String | Long union)
  *   - JsonRpcError
  *   - Message enum (discriminated union)
  *   - Content enum (type discriminator)
  *   - PromptRole enum
  */
object CirceCodecs:

  // RequestId codecs (String | Long union type)
  given Encoder[RequestId] = Encoder.instance { id =>
    id.value match
      case s: String => s.asJson
      case l: Long   => l.asJson
  }

  given Decoder[RequestId] = Decoder.instance { cursor =>
    cursor
      .as[String]
      .map(RequestId.fromString)
      .orElse(cursor.as[Long].map(RequestId.fromLong))
  }

  // JsonRpcError
  given Encoder[JsonRpcError] with

    def apply(error: JsonRpcError): Json = Json.obj(
      "code"    -> error.code.asJson,
      "message" -> error.message.asJson,
      "data"    -> error.data.asJson
    )

  given Decoder[JsonRpcError] = Decoder.instance { cursor =>
    for
      code    <- cursor.get[Int]("code")
      message <- cursor.get[String]("message")
      data    <- cursor.get[Option[Json]]("data")
    yield JsonRpcError(code, message, data)
  }

  // Message enum with discriminated union based on field presence
  given Encoder[Message] = Encoder.instance {
    case Message.Request(jsonrpc, id, method, params) =>
      Json.obj(
        "jsonrpc" -> jsonrpc.asJson,
        "id"      -> id.asJson,
        "method"  -> method.asJson,
        "params"  -> params.asJson
      )

    case Message.Response(jsonrpc, id, result, error) =>
      Json
        .obj(
          "jsonrpc" -> jsonrpc.asJson,
          "id"      -> id.asJson,
          "result"  -> result.asJson,
          "error"   -> error.asJson
        )
        .deepDropNullValues

    case Message.Notification(jsonrpc, method, params) =>
      Json.obj(
        "jsonrpc" -> jsonrpc.asJson,
        "method"  -> method.asJson,
        "params"  -> params.asJson
      )
  }

  given Decoder[Message] = Decoder.instance { cursor =>
    val hasId     = cursor.downField("id").succeeded
    val hasResult = cursor.downField("result").succeeded
    val hasError  = cursor.downField("error").succeeded

    if hasId && (hasResult || hasError) then
      // Response
      for
        jsonrpc <- cursor.get[String]("jsonrpc")
        id      <- cursor.get[RequestId]("id")
        result  <- cursor.get[Option[Json]]("result")
        error   <- cursor.get[Option[JsonRpcError]]("error")
      yield Message.Response(jsonrpc, id, result, error)
    else if hasId then
      // Request
      for
        jsonrpc <- cursor.get[String]("jsonrpc")
        id      <- cursor.get[RequestId]("id")
        method  <- cursor.get[String]("method")
        params  <- cursor.get[Option[Json]]("params")
      yield Message.Request(jsonrpc, id, method, params)
    else
      // Notification
      for
        jsonrpc <- cursor.get[String]("jsonrpc")
        method  <- cursor.get[String]("method")
        params  <- cursor.get[Option[Json]]("params")
      yield Message.Notification(jsonrpc, method, params)
  }

  // Content enum with explicit type discriminator. Optional `annotations` and `_meta` are appended uniformly.
  // Output is run through `deepDropNullValues` so absent options never appear on the wire.
  given Encoder[Content] = Encoder.instance { c =>
    val base = c match
      case Content.Text(text, annotations, meta) =>
        Json.obj(
          "type"        -> "text".asJson,
          "text"        -> text.asJson,
          "annotations" -> annotations.asJson,
          "_meta"       -> meta.asJson
        )

      case Content.Image(data, mimeType, annotations, meta) =>
        Json.obj(
          "type"        -> "image".asJson,
          "data"        -> data.asJson,
          "mimeType"    -> mimeType.asJson,
          "annotations" -> annotations.asJson,
          "_meta"       -> meta.asJson
        )

      case Content.Audio(data, mimeType, annotations, meta) =>
        Json.obj(
          "type"        -> "audio".asJson,
          "data"        -> data.asJson,
          "mimeType"    -> mimeType.asJson,
          "annotations" -> annotations.asJson,
          "_meta"       -> meta.asJson
        )

      case Content.Resource(uri, mimeType, text, blob, annotations, meta) =>
        Json.obj(
          "type"        -> "resource".asJson,
          "uri"         -> uri.asJson,
          "mimeType"    -> mimeType.asJson,
          "text"        -> text.asJson,
          "blob"        -> blob.asJson,
          "annotations" -> annotations.asJson,
          "_meta"       -> meta.asJson
        )

      case Content.ResourceLink(uri, name, title, description, mimeType, size, icons, annotations, meta) =>
        Json.obj(
          "type"        -> "resource_link".asJson,
          "uri"         -> uri.asJson,
          "name"        -> name.asJson,
          "title"       -> title.asJson,
          "description" -> description.asJson,
          "mimeType"    -> mimeType.asJson,
          "size"        -> size.asJson,
          "icons"       -> icons.asJson,
          "annotations" -> annotations.asJson,
          "_meta"       -> meta.asJson
        )
    base.deepDropNullValues
  }

  given Decoder[Content] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "text" =>
        for
          text        <- cursor.get[String]("text")
          annotations <- cursor.get[Option[Annotations]]("annotations")
          meta        <- cursor.get[Option[JsonObject]]("_meta")
        yield Content.Text(text, annotations, meta)

      case "image" =>
        for
          data        <- cursor.get[String]("data")
          mimeType    <- cursor.get[String]("mimeType")
          annotations <- cursor.get[Option[Annotations]]("annotations")
          meta        <- cursor.get[Option[JsonObject]]("_meta")
        yield Content.Image(data, mimeType, annotations, meta)

      case "audio" =>
        for
          data        <- cursor.get[String]("data")
          mimeType    <- cursor.get[String]("mimeType")
          annotations <- cursor.get[Option[Annotations]]("annotations")
          meta        <- cursor.get[Option[JsonObject]]("_meta")
        yield Content.Audio(data, mimeType, annotations, meta)

      case "resource" =>
        for
          uri         <- cursor.get[String]("uri")
          mimeType    <- cursor.get[Option[String]]("mimeType")
          text        <- cursor.get[Option[String]]("text")
          blob        <- cursor.get[Option[String]]("blob")
          annotations <- cursor.get[Option[Annotations]]("annotations")
          meta        <- cursor.get[Option[JsonObject]]("_meta")
        yield Content.Resource(uri, mimeType, text, blob, annotations, meta)

      case "resource_link" =>
        for
          uri         <- cursor.get[String]("uri")
          name        <- cursor.get[String]("name")
          title       <- cursor.get[Option[String]]("title")
          description <- cursor.get[Option[String]]("description")
          mimeType    <- cursor.get[Option[String]]("mimeType")
          size        <- cursor.get[Option[Long]]("size")
          icons       <- cursor.get[Option[List[Icon]]]("icons")
          annotations <- cursor.get[Option[Annotations]]("annotations")
          meta        <- cursor.get[Option[JsonObject]]("_meta")
        yield Content.ResourceLink(uri, name, title, description, mimeType, size, icons, annotations, meta)

      case other =>
        Left(DecodingFailure(s"Unknown content type: $other", cursor.history))
    }
  }

  // PromptRole enum
  given Encoder[PromptRole] = Encoder.instance {
    case PromptRole.User      => "user".asJson
    case PromptRole.Assistant => "assistant".asJson
  }

  given Decoder[PromptRole] = Decoder.instance { cursor =>
    cursor.as[String].flatMap {
      case "user"      => Right(PromptRole.User)
      case "assistant" => Right(PromptRole.Assistant)
      case other       => Left(DecodingFailure(s"Unknown role: $other", cursor.history))
    }
  }
