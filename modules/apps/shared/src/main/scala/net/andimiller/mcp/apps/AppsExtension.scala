package net.andimiller.mcp.apps

/** Constants for the MCP Apps extension (`io.modelcontextprotocol/ui`).
  *
  * Spec: https://github.com/modelcontextprotocol/ext-apps
  */
object AppsExtension:

  /** Identifier used as `capabilities.extensions[key]` on the wire when declaring MCP Apps support. Distinct from the
    * per-item [[MetaKey]] which the spec uses for `_meta.ui.*`.
    */
  val ExtensionKey: String = "io.modelcontextprotocol/ui"

  /** Per-tool and per-resource `_meta` key under which the typed `ui` configuration is stored. The spec uses the bare
    * string `"ui"` (e.g. `_meta.ui.resourceUri`), not the fully-qualified extension key.
    */
  val MetaKey: String = "ui"

  /** Mime type that marks a resource as an MCP App iframe document. */
  val MimeType: String = "text/html;profile=mcp-app"

  /** URI scheme prefix the spec reserves for app resources. */
  val UriPrefix: String = "ui://"

  /** Stable spec version this implementation targets. */
  val SpecVersion: String = "2026-01-26"
