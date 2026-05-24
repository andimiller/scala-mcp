package net.andimiller.mcp.apps.tyrian

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject

/** Display modes the iframe can request via `ui/request-display-mode`. Host may downgrade. */
enum DisplayMode derives CanEqual:
  case Inline
  case Fullscreen
  case Pip

object DisplayMode:

  given Encoder[DisplayMode] = Encoder[String].contramap {
    case Inline     => "inline"
    case Fullscreen => "fullscreen"
    case Pip        => "pip"
  }

  given Decoder[DisplayMode] = Decoder[String].emap {
    case "inline"     => Right(Inline)
    case "fullscreen" => Right(Fullscreen)
    case "pip"        => Right(Pip)
    case other        => Left(s"Unknown display mode: $other")
  }

/** Method strings the spec uses on the wire. Constants kept together so tests and the parser stay in sync.
  */
object BridgeMethods:

  // View → Host requests
  val UiInitialize        = "ui/initialize"
  val UiOpenLink          = "ui/open-link"
  val UiMessage           = "ui/message"
  val UiRequestDisplayMode = "ui/request-display-mode"
  val UiUpdateModelContext = "ui/update-model-context"

  // View → Host notifications
  val UiNotificationsInitialized = "ui/notifications/initialized"

  // Host → View notifications
  val UiNotificationsToolInputPartial    = "ui/notifications/tool-input-partial"
  val UiNotificationsToolInput           = "ui/notifications/tool-input"
  val UiNotificationsToolResult          = "ui/notifications/tool-result"
  val UiNotificationsToolCancelled       = "ui/notifications/tool-cancelled"
  val UiNotificationsHostContextChanged  = "ui/notifications/host-context-changed"
  val UiNotificationsSizeChanged         = "ui/notifications/size-changed"

  // Host → View requests
  val UiResourceTeardown = "ui/resource-teardown"
  val Ping               = "ping"

  // Proxied to server (same shape as core MCP)
  val ToolsCall            = "tools/call"
  val ResourcesRead        = "resources/read"
  val NotificationsMessage = "notifications/message"

/** JSON-RPC 2.0 envelope helpers. We don't decode generic responses here — `BridgeParser` discriminates by `method`
  * presence or `id`/`result`/`error` fields directly.
  */
object JsonRpc:

  val Version: String = "2.0"

  /** Build a request envelope with an integer id. */
  def request(id: Long, method: String, params: Json): Json =
    Json.obj(
      "jsonrpc" -> Json.fromString(Version),
      "id"      -> Json.fromLong(id),
      "method"  -> Json.fromString(method),
      "params"  -> params
    )

  def notification(method: String, params: Json): Json =
    Json.obj(
      "jsonrpc" -> Json.fromString(Version),
      "method"  -> Json.fromString(method),
      "params"  -> params
    )

  /** Build a response envelope with a result. */
  def response(id: Json, result: Json): Json =
    Json.obj(
      "jsonrpc" -> Json.fromString(Version),
      "id"      -> id,
      "result"  -> result
    )

  /** Build an error response envelope. */
  def errorResponse(id: Json, code: Int, message: String, data: Option[Json] = None): Json =
    val errObj = JsonObject(
      "code"    -> Json.fromInt(code),
      "message" -> Json.fromString(message)
    )
    val errWithData = data.fold(errObj)(d => errObj.add("data", d))
    Json.obj(
      "jsonrpc" -> Json.fromString(Version),
      "id"      -> id,
      "error"   -> Json.fromJsonObject(errWithData)
    )
