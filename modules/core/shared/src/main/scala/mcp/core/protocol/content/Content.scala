package mcp.core.protocol.content

/** MCP content types that can be sent between client and server */
enum Content:
  /** Plain text content */
  case Text(text: String)

  /** Base64-encoded image content */
  case Image(
    data: String,      // Base64-encoded image data
    mimeType: String   // e.g., "image/png", "image/jpeg"
  )

  /** Base64-encoded audio content */
  case Audio(
    data: String,      // Base64-encoded audio data
    mimeType: String   // e.g., "audio/wav", "audio/mp3"
  )

  /** Embedded resource content */
  case Resource(
    uri: String,
    mimeType: Option[String] = None,
    text: Option[String] = None
  )

object Content:
  def text(text: String): Content = Text(text)

  def image(data: String, mimeType: String): Content =
    Image(data, mimeType)

  def audio(data: String, mimeType: String): Content =
    Audio(data, mimeType)

  def resource(uri: String, mimeType: Option[String] = None, text: Option[String] = None): Content =
    Resource(uri, mimeType, text)
