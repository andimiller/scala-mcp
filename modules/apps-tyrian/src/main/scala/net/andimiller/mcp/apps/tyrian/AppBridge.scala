package net.andimiller.mcp.apps.tyrian

import cats.effect.kernel.Async
import cats.effect.kernel.Sync

import io.circe.Json
import io.circe.syntax.*

import org.scalajs.dom

import tyrian.Cmd
import tyrian.Sub

import scala.scalajs.js

/** Typed AppBridge for an MCP App iframe built with Tyrian.
  *
  * The bridge is the JS-side counterpart of the MCP Apps host: it sends JSON-RPC frames out via
  * `window.parent.postMessage`, listens for inbound frames on `window.addEventListener("message", …)`, and surfaces them
  * as a [[Sub]] of typed [[HostEvent]]s.
  *
  * V1 surface is fire-and-forget — outbound `Cmd`s return `Cmd[F, Nothing]` because we don't await responses (the host
  * surfaces tool results, host-context changes, etc. via notifications). Request/response correlation for
  * `tools/call`/`resources/read` is a follow-up.
  *
  * State (the next request id, the id used for `ui/initialize` so the parser can synthesise `HostEvent.Ready`) lives in
  * mutable vars because JS is single-threaded and we want a frictionless API. If you need test isolation, use the
  * [[AppBridge.fresh]] factory which returns a self-contained bridge instance.
  */
trait AppBridge[F[_]]:

  /** Subscribe to host → iframe messages.
    *
    * @param allowedOrigins
    *   if non-empty, drop messages whose `MessageEvent.origin` isn't in the set. Sandboxed iframes typically see
    *   `"null"`; explicit allowlists are stronger but harder to ship.
    * @param autoNotifyInitialized
    *   when `true` (the default), the listener side-effectfully posts `ui/notifications/initialized` whenever the
    *   spec-mandated [[HostEvent.Ready]] is synthesised, so users don't have to wire the handshake completion
    *   themselves. Set to `false` to take manual control.
    */
  def events(allowedOrigins: Set[String] = Set.empty, autoNotifyInitialized: Boolean = true): Sub[F, HostEvent]

  /** Send the handshake `ui/initialize` request. The id is recorded internally so that the response is recognised as
    * `HostEvent.Ready(hostContext)` when it arrives via [[events]].
    */
  def initialize(appInfo: Json, appCapabilities: Json): Cmd[F, Nothing]

  /** Send `ui/notifications/initialized`. Per spec, this should fire AFTER `HostEvent.Ready` has been received, not
    * immediately after [[initialize]]. The default `events` Sub auto-fires this for you on Ready — most users won't
    * need to call this directly.
    */
  def notifyInitialized: Cmd[F, Nothing]

  /** Notify the host that the iframe content's pixel size has changed. Claude Desktop (and other hosts) keep the
    * iframe container at `visibility: hidden` until at least one `size-changed` notification arrives — the spec
    * requires this notification right after `initialized` to flip the iframe visible. The default `events` Sub
    * auto-fires this for you on Ready using the document's current dimensions.
    */
  def sendSizeChanged(width: Double, height: Double): Cmd[F, Nothing]

  /** Ask the host to open `url` in a new tab / external browser. */
  def openLink(url: String): Cmd[F, Nothing]

  /** Send a chat message back into the host's transcript. */
  def sendMessage(role: String, contentText: String): Cmd[F, Nothing]

  /** Request a display mode change. Host may downgrade (e.g. block fullscreen). */
  def requestDisplayMode(mode: DisplayMode): Cmd[F, Nothing]

  /** Inject extra content into the next LLM turn — either freeform string or structured JSON. */
  def updateModelContext(content: Option[String], structuredContent: Option[Json]): Cmd[F, Nothing]

  /** Ask the host to invoke a server tool (proxied via the host). Fire-and-forget — the result arrives back via the
    * normal `HostEvent.ToolResult` notification when the tool has a UI. For tools without a UI (or when you need the
    * result inline), full request/response correlation will land in a follow-up.
    */
  def callTool(name: String, arguments: Json): Cmd[F, Nothing]

  /** Ask the host to read an MCP resource. Fire-and-forget — see [[callTool]] note about response handling. */
  def readResource(uri: String): Cmd[F, Nothing]

  /** Build an arbitrary outbound JSON-RPC notification with given method + params. Escape hatch. */
  def notification(method: String, params: Json): Cmd[F, Nothing]

object AppBridge:

  /** The id used in `Sub.make` for the bridge's inbound listener. Exposed so users adding their own `message` event
    * subscriptions can pick a different id and avoid the documented Tyrian `Sub.fromEvent` collision (subs with
    * matching ids are deduplicated).
    */
  val SubscriptionId: String = "apps-tyrian/host-message"

  /** Build a fresh bridge with its own request-id counter and pending-id state. Typically called once per iframe and
    * stored as a `val` (or supplied by [[TyrianMcpApp]]).
    */
  def apply[F[_]: Async](): AppBridge[F] = new Impl[F]

  private final class Impl[F[_]: Async] extends AppBridge[F]:

    private var nextId: Long                       = 1L
    @volatile private var initializeIds: Set[Json] = Set.empty
    @volatile private var toolCallIds: Set[Json]   = Set.empty

    private def allocateId(): Long =
      val id = nextId
      nextId = nextId + 1
      id

    private def post(json: Json): Cmd[F, Nothing] =
      Cmd.SideEffect(postRaw(json))

    private def pending: BridgeParser.PendingRequests =
      BridgeParser.PendingRequests.of(initializeIds, toolCallIds)

    def events(
        allowedOrigins: Set[String] = Set.empty,
        autoNotifyInitialized: Boolean = true
    ): Sub[F, HostEvent] =
      Sub.make[F, HostEvent, js.Function1[dom.MessageEvent, Unit]](SubscriptionId) { callback =>
        Sync[F].delay {
          val listener: js.Function1[dom.MessageEvent, Unit] = (ev: dom.MessageEvent) =>
            if allowedOrigins.isEmpty || allowedOrigins.contains(ev.origin) then
              val raw = ev.data match
                case s: String => s
                case other     => js.JSON.stringify(other.asInstanceOf[js.Any])
              val event = BridgeParser.parse(raw, pending)
              event match
                case HostEvent.Ready(_) if autoNotifyInitialized =>
                  // Spec ordering: initialized notification follows the ui/initialize response, then size-changed.
                  // Claude Desktop gates iframe visibility on size-changed (ext-apps#615 / claude-ai-mcp#149) — without
                  // it the iframe container stays `visibility: hidden` and the user sees "stuck loading".
                  postRaw(JsonRpc.notification(BridgeMethods.UiNotificationsInitialized, Json.obj()))
                  postSizeChanged()
                case _ => ()
              callback(Right(event))
          dom.window.addEventListener("message", listener)
          listener
        }
      } { listener =>
        Sync[F].delay(dom.window.removeEventListener("message", listener))
      }

    private def postRaw(json: Json): Unit =
      // CRITICAL: post the raw JS object, NOT a stringified JSON. The MCP Apps host transport (Claude Desktop's
      // `@modelcontextprotocol/ext-apps` SDK) runs `JSONRPCMessageSchema.safeParse(event.data)` — when event.data is a
      // string instead of an object it silently fails validation and drops the message. Reference:
      // ext-apps/src/message-transport.ts:73-133. We use `js.JSON.parse` to round-trip our circe Json into a native JS
      // object value.
      val serialized = json.deepDropNullValues.noSpaces
      val asJsObject = js.JSON.parse(serialized).asInstanceOf[js.Any]
      val target     = if dom.window.parent != null then dom.window.parent else dom.window
      target.postMessage(asJsObject, "*")

    def initialize(appInfo: Json, appCapabilities: Json): Cmd[F, Nothing] =
      Cmd.SideEffect {
        val id = allocateId()
        initializeIds = initializeIds + Json.fromLong(id)
        // Spec/SDK use `appInfo` + `appCapabilities` (NOT the regular MCP `clientInfo`/`capabilities`). Claude Desktop
        // schema-validates and rejects the request silently if the wrong names are used (see ext-apps#634).
        val params = Json.obj(
          "protocolVersion" -> Json.fromString("2026-01-26"),
          "appInfo"         -> appInfo,
          "appCapabilities" -> appCapabilities
        )
        postRaw(JsonRpc.request(id, BridgeMethods.UiInitialize, params))
      }

    def sendSizeChanged(width: Double, height: Double): Cmd[F, Nothing] =
      post(
        JsonRpc.notification(
          BridgeMethods.UiNotificationsSizeChanged,
          Json.obj("width" -> Json.fromDoubleOrNull(width), "height" -> Json.fromDoubleOrNull(height))
        )
      )

    private def currentSize: (Double, Double) =
      val w = math.max(dom.window.innerWidth.toDouble, dom.document.documentElement.clientWidth.toDouble)
      val h =
        math.max(dom.document.documentElement.scrollHeight.toDouble, dom.document.documentElement.clientHeight.toDouble)
      (w, h)

    private def postSizeChanged(): Unit =
      val (w, h) = currentSize
      postRaw(
        JsonRpc.notification(
          BridgeMethods.UiNotificationsSizeChanged,
          Json.obj("width" -> Json.fromDoubleOrNull(w), "height" -> Json.fromDoubleOrNull(h))
        )
      )

    def notifyInitialized: Cmd[F, Nothing] =
      post(JsonRpc.notification(BridgeMethods.UiNotificationsInitialized, Json.obj()))

    def openLink(url: String): Cmd[F, Nothing] =
      post(JsonRpc.request(allocateId(), BridgeMethods.UiOpenLink, Json.obj("url" -> Json.fromString(url))))

    def sendMessage(role: String, contentText: String): Cmd[F, Nothing] =
      val params = Json.obj(
        "role"    -> Json.fromString(role),
        "content" -> Json.obj(
          "type" -> Json.fromString("text"),
          "text" -> Json.fromString(contentText)
        )
      )
      post(JsonRpc.request(allocateId(), BridgeMethods.UiMessage, params))

    def requestDisplayMode(mode: DisplayMode): Cmd[F, Nothing] =
      post(JsonRpc.request(allocateId(), BridgeMethods.UiRequestDisplayMode, Json.obj("mode" -> mode.asJson)))

    def updateModelContext(content: Option[String], structuredContent: Option[Json]): Cmd[F, Nothing] =
      val fields: List[(String, Json)] =
        List(
          content.map(c => "content" -> Json.fromString(c)),
          structuredContent.map(s => "structuredContent" -> s)
        ).flatten
      post(JsonRpc.request(allocateId(), BridgeMethods.UiUpdateModelContext, Json.fromFields(fields)))

    def callTool(name: String, arguments: Json): Cmd[F, Nothing] =
      Cmd.SideEffect {
        val id = allocateId()
        toolCallIds = toolCallIds + Json.fromLong(id)
        val params = Json.obj("name" -> Json.fromString(name), "arguments" -> arguments)
        postRaw(JsonRpc.request(id, BridgeMethods.ToolsCall, params))
      }

    def readResource(uri: String): Cmd[F, Nothing] =
      post(JsonRpc.request(allocateId(), BridgeMethods.ResourcesRead, Json.obj("uri" -> Json.fromString(uri))))

    def notification(method: String, params: Json): Cmd[F, Nothing] =
      post(JsonRpc.notification(method, params))
