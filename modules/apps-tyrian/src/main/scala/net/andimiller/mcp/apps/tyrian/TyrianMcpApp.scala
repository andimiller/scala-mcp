package net.andimiller.mcp.apps.tyrian

import cats.effect.IO

import io.circe.Json

import tyrian.*

/** Convenience base trait for a Tyrian MCP App iframe. Pre-wires the JSON-RPC handshake (`ui/initialize` →
  * `ui/notifications/initialized`) and the inbound [[HostEvent]] subscription so the user only has to define their
  * model, view, update, and a `HostEvent => Msg` translator.
  *
  * Usage:
  * {{{
  *   object MyApp extends TyrianMcpApp[Msg, Model]:
  *     val appInfo: Json = Json.obj("name" -> "my-app".asJson, "version" -> "1.0.0".asJson)
  *
  *     def onAppEvent(event: HostEvent): Msg = Msg.FromHost(event)
  *
  *     def initApp(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = ...
  *     def updateApp(model: Model): Msg => (Model, Cmd[IO, Msg]) = ...
  *     def viewApp(model: Model): Html[Msg] = ...
  *     def subscriptionsApp(model: Model): Sub[IO, Msg] = Sub.None
  *     def routerApp: Location => Msg = Routing.none(...)
  *
  *     def main(args: Array[String]): Unit = launch("main")
  * }}}
  */
abstract class TyrianMcpApp[Msg, Model] extends TyrianIOApp[Msg, Model]:

  /** Identification of this app sent in `ui/initialize.clientInfo`. Typically an `Implementation` encoded to JSON, but
    * any JSON object is accepted.
    */
  def appInfo: Json

  /** App-side capabilities sent in `ui/initialize.appCapabilities`. Defaults to `{}`. */
  def appCapabilities: Json = Json.obj()

  /** Origins to accept inbound `message` events from. Empty (default) means accept all — fine for most iframe contexts
    * where the parent is trusted; set to a specific set for hardened deployments.
    */
  def allowedOrigins: Set[String] = Set.empty

  /** Map an inbound host event into the user's `Msg` ADT. Called for every event surfaced via [[AppBridge.events]],
    * including `Ready`, `ToolResult`, `Malformed`, etc.
    */
  def onAppEvent(event: HostEvent): Msg

  /** User-supplied init — equivalent to the Tyrian `init`. The framework wraps it to also send `ui/initialize`. */
  def initApp(flags: Map[String, String]): (Model, Cmd[IO, Msg])

  /** User-supplied update — equivalent to the Tyrian `update`. */
  def updateApp(model: Model): Msg => (Model, Cmd[IO, Msg])

  /** User-supplied view — equivalent to the Tyrian `view`. */
  def viewApp(model: Model): Html[Msg]

  /** User-supplied subscriptions. Composed with the bridge's inbound events sub via `|+|`. Default: empty. */
  def subscriptionsApp(model: Model): Sub[IO, Msg] = Sub.None

  /** User-supplied router. Default: no-op routing (single-page iframe). */
  def routerApp: Location => Msg

  /** The bridge instance backing this app. Override if you need a non-default bridge for testing. */
  protected val bridge: AppBridge[IO] = AppBridge[IO]()

  final def router: Location => Msg = routerApp

  final def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    val (m, cmd) = initApp(flags)
    // Spec ordering says `initialized` and `size-changed` should follow the `ui/initialize` response, but in practice
    // Claude Desktop (and likely others) gate iframe visibility on `size-changed` arriving — and the response may never
    // come back (host bug, message id mismatch, or our listener installs after the response). Send all three eagerly
    // in init; they are idempotent (the listener will re-send `initialized` + `size-changed` if Ready also arrives).
    val initialSize = bridge.sendSizeChanged(
      math.max(org.scalajs.dom.window.innerWidth.toDouble, 320.0),
      math.max(org.scalajs.dom.document.documentElement.scrollHeight.toDouble, 240.0)
    )
    (
      m,
      cmd |+| bridge.initialize(appInfo, appCapabilities) |+| bridge.notifyInitialized |+| initialSize
    )

  final def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = updateApp(model)

  final def view(model: Model): Html[Msg] = viewApp(model)

  final def subscriptions(model: Model): Sub[IO, Msg] =
    subscriptionsApp(model) |+| bridge.events(allowedOrigins).map(onAppEvent)
