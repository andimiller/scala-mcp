package net.andimiller.mcp.apps

import io.circe.Decoder
import io.circe.Encoder

/** Whether a given app tool is visible to the LLM, to the iframe, or both.
  *
  * Per spec, omitting `visibility` entirely means "both" — represented here as `ToolUiMetadata(visibility = None)`.
  */
enum ToolVisibility derives CanEqual:
  case Model
  case App

object ToolVisibility:

  given Encoder[ToolVisibility] = Encoder[String].contramap {
    case Model => "model"
    case App   => "app"
  }

  given Decoder[ToolVisibility] = Decoder[String].emap {
    case "model" => Right(Model)
    case "app"   => Right(App)
    case other   => Left(s"Unknown tool visibility: $other")
  }

/** `_meta.io.modelcontextprotocol/ui` payload for a tool.
  *
  *   - `resourceUri` — the `ui://...` URI of the resource the host should render when this tool is invoked.
  *   - `visibility` — `None` to use the spec default (both LLM- and app-visible); `Some(List(Model))` to hide from the
  *     iframe; `Some(List(App))` to hide from the LLM. `Some(Nil)` is explicitly empty and means neither, which is
  *     spec-legal but probably an error.
  */
case class ToolUiMetadata(
    resourceUri: String,
    visibility: Option[List[ToolVisibility]] = None
) derives Encoder.AsObject,
      Decoder
