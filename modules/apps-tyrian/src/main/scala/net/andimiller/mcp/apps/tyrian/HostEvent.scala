package net.andimiller.mcp.apps.tyrian

import io.circe.Json

/** Events the iframe receives from its hosting MCP client.
  *
  * Most payloads are typed as raw `Json` in v1: the spec's `hostContext`, `tool-input`, `tool-result`, and `size` shapes
  * are open-ended and host-defined. Users decode the bits they care about; we'll add typed wrappers in follow-ups as
  * stable patterns emerge.
  *
  * `Ready` is synthesised by [[BridgeParser]] when it sees a JSON-RPC response to the `ui/initialize` request — it is
  * not a wire-level method.
  */
enum HostEvent derives CanEqual:

  /** Handshake complete. The host's response to our `ui/initialize` is in `hostContext` (`McpUiInitializeResult.hostContext`).
    */
  case Ready(hostContext: Json)

  /** Partial tool-input streamed during a long-running tool call. */
  case ToolInputPartial(params: Json)

  /** Final tool-input emitted just before the tool runs. */
  case ToolInput(params: Json)

  /** Tool finished — `params` is the host's framing of the tool's `CallToolResponse`. */
  case ToolResult(params: Json)

  /** Tool was cancelled (by user or host). */
  case ToolCancelled(params: Json)

  /** Host-context fields changed (theme, display mode, container size, etc.). */
  case HostContextChanged(params: Json)

  /** Iframe container was resized. */
  case SizeChanged(params: Json)

  /** Host asked the iframe to clean up before the resource is unloaded. */
  case ResourceTeardown(requestId: Json)

  /** Host pinged the iframe to check liveness. */
  case Ping(requestId: Json)

  /** Inbound message didn't parse as a JSON-RPC frame the bridge knows about. */
  case Malformed(raw: String, reason: String)
