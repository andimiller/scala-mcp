# DNS MCP server

`modules/example-dns-mcp` · HTTP + SSE · Scala.js (Node.js) · port 8053

A Scala.js server running on Node.js, demonstrating how to wrap Node.js
callback-based APIs (`dns` module) into cats-effect `IO` via `IO.async_`. Uses
the HTTP transport, which allows concurrent requests — a good fit for the
async nature of Node.js.

- **Tools:** `resolve_dns` (A, AAAA, MX, TXT, CNAME, NS records), `reverse_dns` (IP to hostnames)
- **Resource:** `dns://reference/record-types` (static markdown reference)
- **Prompt:** `diagnose_dns` — comprehensive DNS diagnosis for a domain

## Build and run

```bash
# Link the Scala.js output
sbt exampleDns/fastLinkJS

# Run on Node.js
node modules/example-dns-mcp/target/scala-3.3.4/example-dns-mcp-fastopt/main.js
# Server starts on http://0.0.0.0:8053
```

## Configure in Claude Code

`.mcp.json`:

```json
{
  "mcpServers": {
    "dns": {
      "type": "streamable-http",
      "url": "http://localhost:8053/mcp"
    }
  }
}
```
