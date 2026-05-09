# CLI client (mcp-client)

`modules/example-client` · Stdio + HTTP · JVM

A tiny interactive REPL over any MCP server — handy for sanity-checking a
server you're writing, and a worked example of using `StdioMcpClient` and
`StreamableHttpMcpClient` together behind a single `decline` CLI.

- **Subcommands:** `mcp-client stdio <command> [args…]` spawns a subprocess
  server; `mcp-client http <url> [-H 'k: v']…` connects to a streamable
  HTTP server.
- **REPL keys:** `t` lists tools, `r` lists resources, `p` lists prompts,
  `q` quits. Each lists the entries with numbers, lets you pick one, and
  invokes it (tool calls prompt for JSON arguments; prompts ask for each
  defined argument).
- **Capabilities banner:** prints the server's name, version, protocol
  version, and which capabilities it advertised during `initialize`.

## Build and run

```bash
# Stdio: spawn a subprocess MCP server
sbt 'exampleClient/run stdio sbt exampleDiceJVM/run'

# HTTP: connect to a networked MCP server
sbt 'exampleClient/run http http://localhost:25000/mcp'

# HTTP with a bearer token
sbt 'exampleClient/run http https://my-server/mcp -H "Authorization: Bearer …"'
```

`--no-sse` disables the long-poll SSE GET if you only want plain
request/response over HTTP POST.

## What it shows

The whole client is ~280 lines in
`modules/example-client/src/main/scala/net/andimiller/mcp/examples/client/McpCliClient.scala`.
Main shapes worth lifting into your own code:

- One CLI binary covering both transports — `decline` subcommands feed
  into a single `Resource[IO, McpClient[IO]]` (see
  `stdioResource` / `httpResource` in the source).
- `StdioMcpClient.builder` and `StreamableHttpMcpClient.builder` produce
  the same `McpClient[IO]` shape, so the REPL itself is transport-agnostic.
- `client.serverCapabilities.{tools,resources,prompts,logging}` — used to
  print a capability summary on connect.

For a runnable agent example that drives multiple servers from a Claude-style
`.mcp.json`, see the [LLM harness](harness.md).
