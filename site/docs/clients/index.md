# Clients

scala-mcp also ships an `McpClient` API — the symmetric counterpart to the
server side. Use it to drive an MCP server from your own code: scripting tool
calls, testing servers you've written, or building an LLM agent that consumes
MCP tools and resources.

| Transport | Module | Platforms | Use it for |
|-----------|--------|-----------|------------|
| Stdio | [`mcp-stdio`](../modules/stdio.md) | JVM, JS, Native | Spawning a subprocess MCP server (Claude-style `.mcp.json` `command` entries) |
| Streamable HTTP + SSE | [`mcp-http4s`](../modules/http4s.md) | JVM, JS, Native | Connecting to a networked MCP server (`type: "http"` entries, remote agents) |

Both transports have the same shape:

- a low-level entry point (`StdioMcpClient.fromStreams` /
  `StreamableHttpMcpClient.fromHttpClient`) that yields an
  `UninitializedMcpClient[F]` so the caller controls when the JSON-RPC
  `initialize` handshake runs;
- a fluent **builder** (`StdioMcpClient.builder` /
  `StreamableHttpMcpClient.builder`) that wraps "open transport + initialize"
  into a single `Resource[F, McpClient[F]]`.

Once initialized, `McpClient[F]` exposes typed methods for every server-side
capability — `listTools` / `callTool`, `listResources` / `readResource` /
`subscribe`, `listPrompts` / `getPrompt`, `ping`, plus a `notifications`
stream of server-initiated notifications.

## Where to next

- **[Client construction](client-construction.md)** — connect over stdio or
  HTTP, walk through the `McpClient` API, consume notifications.
- **[Client handlers](client-handlers.md)** — respond to server-initiated
  requests (`sampling/createMessage`, `elicitation/create`, `roots/list`)
  and advertise matching `ClientCapabilities`.
- **[Examples → CLI client](../examples/cli-client.md)** — a tiny REPL over
  any MCP server (`mcp-client stdio …` / `mcp-client http …`).
- **[Examples → LLM harness](../examples/harness.md)** — a Claude-style
  `.mcp.json`-driven agent that bridges every connected server's tools and
  prompts to an OpenAI-compatible chat endpoint.
