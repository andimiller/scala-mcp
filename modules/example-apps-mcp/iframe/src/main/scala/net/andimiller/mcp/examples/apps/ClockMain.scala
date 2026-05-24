package net.andimiller.mcp.examples.apps

import cats.effect.IO

import io.circe.Json
import io.circe.syntax.*

import net.andimiller.mcp.apps.tyrian.*

import tyrian.*
import tyrian.Html.*

enum Msg derives CanEqual:
  case FromHost(event: HostEvent)
  case Refresh
  case OpenDocs
  case GoFullscreen
  case NoOp

case class Model(
    timeFromTool: Option[String],
    lastEvent: Option[String],
    ready: Boolean
)

object Model:
  val initial: Model = Model(None, None, ready = false)

object ClockMain extends TyrianMcpApp[Msg, Model]:

  def main(args: Array[String]): Unit = launch("main")

  val appInfo: Json = Json.obj(
    "name"    -> "scala-mcp-clock".asJson,
    "version" -> "0.1.0".asJson,
    "title"   -> "Clock".asJson
  )

  def onAppEvent(event: HostEvent): Msg = Msg.FromHost(event)

  def initApp(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model.initial, Cmd.None)

  def updateApp(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.FromHost(HostEvent.Ready(_))             =>
      (model.copy(ready = true, lastEvent = Some("ready")), Cmd.None)
    case Msg.FromHost(HostEvent.ToolResult(params))   =>
      val text = params.hcursor
        .downField("structuredContent")
        .downField("isoTime")
        .as[String]
        .toOption
        .orElse(params.hcursor.downField("content").downArray.downField("text").as[String].toOption)
      (model.copy(timeFromTool = text, lastEvent = Some("tool-result")), Cmd.None)
    case Msg.FromHost(HostEvent.HostContextChanged(_)) =>
      (model.copy(lastEvent = Some("host-context-changed")), Cmd.None)
    case Msg.FromHost(other)                          =>
      (model.copy(lastEvent = Some(other.getClass.getSimpleName)), Cmd.None)
    case Msg.Refresh                                   =>
      (model.copy(lastEvent = Some("refresh requested")), bridge.callTool("get_time", Json.obj()))
    case Msg.OpenDocs                                  =>
      (model, bridge.openLink("https://github.com/modelcontextprotocol/ext-apps"))
    case Msg.GoFullscreen                              =>
      (model, bridge.requestDisplayMode(DisplayMode.Fullscreen))
    case Msg.NoOp                                      =>
      (model, Cmd.None)
  }

  def viewApp(model: Model): Html[Msg] =
    div(style := "font-family: system-ui, sans-serif; padding: 16px;")(
      h1("Clock (MCP App)"),
      div(style := "margin: 12px 0; font-size: 32px; font-variant-numeric: tabular-nums;")(
        text(model.timeFromTool.getOrElse("(no result yet — invoke get_time)"))
      ),
      div(style := "color: #666; font-size: 12px; margin-bottom: 12px;")(
        text(s"handshake: ${if model.ready then "ready" else "pending"} · last event: ${model.lastEvent.getOrElse("none")}")
      ),
      div(
        button(onClick(Msg.Refresh))("Refresh"),
        text(" "),
        button(onClick(Msg.OpenDocs))("Open MCP Apps docs"),
        text(" "),
        button(onClick(Msg.GoFullscreen))("Go fullscreen")
      )
    )

  def routerApp: Location => Msg = _ => Msg.NoOp
