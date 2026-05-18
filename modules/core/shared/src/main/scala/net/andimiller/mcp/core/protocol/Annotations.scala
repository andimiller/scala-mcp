package net.andimiller.mcp.core.protocol

import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.syntax.*

/** Audience role for content/resource annotations — who should pay attention to this item. */
enum Role:

  case User

  case Assistant

object Role:

  given Encoder[Role] = Encoder.instance:
    case User      => "user".asJson
    case Assistant => "assistant".asJson

  given Decoder[Role] = Decoder.instance: cursor =>
    cursor
      .as[String]
      .flatMap:
        case "user"      => Right(User)
        case "assistant" => Right(Assistant)
        case other       => Left(DecodingFailure(s"Unknown role: $other", cursor.history))

/** Content/resource annotations — hints to clients about how to render or prioritise an item. Distinct from
  * `ToolAnnotations` which carries behavioural hints for tools.
  *
  * @param audience
  *   Which roles should see this item; absent means no restriction.
  * @param priority
  *   Importance in `[0.0, 1.0]`, where `1.0` is most important.
  * @param lastModified
  *   ISO 8601 timestamp of last modification.
  */
case class Annotations(
    audience: Option[List[Role]] = None,
    priority: Option[Double] = None,
    lastModified: Option[String] = None
) derives Encoder.AsObject,
      Decoder
