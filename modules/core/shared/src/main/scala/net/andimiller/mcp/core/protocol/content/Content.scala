package net.andimiller.mcp.core.protocol.content

import net.andimiller.mcp.core.protocol.Annotations
import net.andimiller.mcp.core.protocol.Icon

import io.circe.JsonObject

/** MCP content types that can be sent between client and server.
  *
  * All variants carry optional `annotations` (audience/priority/lastModified hints to the client) and `_meta`
  * (arbitrary extension metadata).
  */
enum Content:

  /** Plain text content */
  case Text(
      text: String,
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

  /** Base64-encoded image content */
  case Image(
      data: String,     // Base64-encoded image data
      mimeType: String, // e.g., "image/png", "image/jpeg"
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

  /** Base64-encoded audio content */
  case Audio(
      data: String,     // Base64-encoded audio data
      mimeType: String, // e.g., "audio/wav", "audio/mp3"
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

  /** Embedded resource content — the resource's bytes/text travel with the message. Either `text` or `blob` may be set
    * (corresponding to `TextResourceContents` and `BlobResourceContents` in the spec).
    */
  case Resource(
      uri: String,
      mimeType: Option[String] = None,
      text: Option[String] = None,
      blob: Option[String] = None, // Base64-encoded binary data
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

  /** A pointer to a resource the client can fetch separately. Distinct from `Resource` (which embeds bytes). */
  case ResourceLink(
      uri: String,
      name: String,
      title: Option[String] = None,
      description: Option[String] = None,
      mimeType: Option[String] = None,
      size: Option[Long] = None,
      icons: Option[List[Icon]] = None,
      annotations: Option[Annotations] = None,
      _meta: Option[JsonObject] = None
  )

object Content:

  def text(text: String): Content = Text(text)

  def image(data: String, mimeType: String): Content =
    Image(data, mimeType)

  def audio(data: String, mimeType: String): Content =
    Audio(data, mimeType)

  def resource(uri: String, mimeType: Option[String] = None, text: Option[String] = None): Content =
    Resource(uri, mimeType, text)

  def resourceLink(uri: String, name: String): Content =
    ResourceLink(uri, name)
