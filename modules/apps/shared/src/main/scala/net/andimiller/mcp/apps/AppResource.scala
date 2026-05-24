package net.andimiller.mcp.apps

import cats.effect.kernel.Async

import io.circe.syntax.*

import net.andimiller.mcp.core.server.McpResource

/** Helpers for constructing the `ui://` HTML resources that an MCP App tool renders. */
object AppResource:

  /** Build a static HTML app resource in one call.
    *
    *   - `uri` must start with `ui://`.
    *   - The mime type is set to `text/html;profile=mcp-app`.
    *   - `ui` is encoded under `_meta.io.modelcontextprotocol/ui`.
    */
  def html[F[_]: Async](
      uri: String,
      name: String,
      html: String,
      ui: ResourceUiMetadata = ResourceUiMetadata(),
      description: Option[String] = None,
      title: Option[String] = None
  ): McpResource[F, Unit] =
    require(uri.startsWith(AppsExtension.UriPrefix), s"App resources must start with ${AppsExtension.UriPrefix}")
    val base    = McpResource.builder
      .uri(uri)
      .name(name)
      .mimeType(AppsExtension.MimeType)
      .meta(AppsExtension.MetaKey, ui.asJson)
    val titled  = title.fold(base)(base.title)
    val described = description.fold(titled)(titled.description)
    described.staticContent[F](html)
