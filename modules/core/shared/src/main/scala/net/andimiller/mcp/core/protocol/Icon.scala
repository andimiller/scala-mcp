package net.andimiller.mcp.core.protocol

import java.nio.charset.StandardCharsets

import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.syntax.*
import scodec.bits.ByteVector

/** Icon theme hint for clients with light/dark UI modes. */
enum IconTheme:

  case Light

  case Dark

object IconTheme:

  given Encoder[IconTheme] = Encoder.instance:
    case Light => "light".asJson
    case Dark  => "dark".asJson

  given Decoder[IconTheme] = Decoder.instance: cursor =>
    cursor
      .as[String]
      .flatMap:
        case "light" => Right(Light)
        case "dark"  => Right(Dark)
        case other   => Left(DecodingFailure(s"Unknown icon theme: $other", cursor.history))

/** A renderable icon associated with a Tool, Resource, ResourceTemplate, Prompt, ResourceLink, or Implementation.
  *
  * `src` must be an HTTPS URL or a `data:` URI; clients must reject `javascript:`, `file:`, `ftp:`, `ws:` and HTTP
  * redirects. MIME types `image/png` and `image/jpeg` must be supported by clients; `image/svg+xml` and `image/webp`
  * should be supported.
  */
case class Icon(
    src: String,
    mimeType: Option[String] = None,
    sizes: Option[List[String]] = None,
    theme: Option[IconTheme] = None
) derives Encoder.AsObject,
      Decoder

object Icon:

  def png(src: String, sizes: List[String] = Nil): Icon =
    Icon(src, mimeType = Some("image/png"), sizes = Option.when(sizes.nonEmpty)(sizes))

  def jpeg(src: String, sizes: List[String] = Nil): Icon =
    Icon(src, mimeType = Some("image/jpeg"), sizes = Option.when(sizes.nonEmpty)(sizes))

  def svg(src: String, sizes: List[String] = Nil): Icon =
    Icon(src, mimeType = Some("image/svg+xml"), sizes = Option.when(sizes.nonEmpty)(sizes))

  def dataUri(mimeType: String, base64Data: String): Icon =
    Icon(s"data:$mimeType;base64,$base64Data", mimeType = Some(mimeType))

  /** Build a `data:` URI Icon by base64-encoding the given string. Useful for inlining small SVGs. */
  def dataEncode(mimeType: String, rawString: String): Icon =
    val encoded = ByteVector.view(rawString.getBytes(StandardCharsets.UTF_8)).toBase64
    Icon(s"data:$mimeType;base64,$encoded", mimeType = Some(mimeType))
