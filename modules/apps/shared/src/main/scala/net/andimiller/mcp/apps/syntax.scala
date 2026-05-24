package net.andimiller.mcp.apps

import cats.effect.kernel.Async

import io.circe.Json
import io.circe.syntax.*

import net.andimiller.mcp.core.server.ResourceBuilder
import net.andimiller.mcp.core.server.ServerBuilder
import net.andimiller.mcp.core.server.ToolBuilder

/** Extension methods that bolt the MCP Apps extension onto the existing core builders. Composes via the per-key
  * `.meta(key, value)` setter so user-supplied `_meta` entries are preserved.
  */
object syntax:

  extension [Ctx, In, Out](b: ToolBuilder.WithIn[Ctx, In, Out])

    /** Attach a typed `_meta.ui` payload to this tool. */
    def appUi(ui: ToolUiMetadata): ToolBuilder.WithIn[Ctx, In, Out] =
      b.meta(AppsExtension.MetaKey, ui.asJson)

    /** Convenience: declare this tool as an MCP App tool whose UI is at `resourceUri`. Visibility defaults to spec
      * default (both LLM- and app-visible). Pass `Some(List(ToolVisibility.App))` to hide from the LLM.
      */
    def asApp(
        resourceUri: String,
        visibility: Option[List[ToolVisibility]] = None
    ): ToolBuilder.WithIn[Ctx, In, Out] =
      appUi(ToolUiMetadata(resourceUri, visibility))

  extension [Ctx](b: ResourceBuilder.Builder[Ctx])

    /** Attach a typed `_meta.ui` payload to this resource. */
    def appUi(ui: ResourceUiMetadata): ResourceBuilder.Builder[Ctx] =
      b.meta(AppsExtension.MetaKey, ui.asJson)

    /** Set the mime type to the MCP App profile (`text/html;profile=mcp-app`). */
    def appHtml: ResourceBuilder.Builder[Ctx] =
      b.mimeType(AppsExtension.MimeType)

  extension [F[_]: Async, Ctx](sb: ServerBuilder[F, Ctx])

    /** Declare the `io.modelcontextprotocol/ui` capability with the standard `mimeTypes` list. */
    def withAppsExtension: ServerBuilder[F, Ctx] =
      sb.withExtension(
        AppsExtension.ExtensionKey,
        Json.obj("mimeTypes" -> Json.arr(Json.fromString(AppsExtension.MimeType)))
      )
