# scala-mcp

A Scala 3 library for building [Model Context Protocol (MCP)](https://modelcontextprotocol.io)
servers. MCP is a JSON-RPC 2.0 based protocol that allows AI applications like
Claude to access tools, resources, and prompts.

## Highlights

- Type-safe protocol implementation with Scala 3 enums and opaque types
- Automatic JSON Schema derivation via `derives JsonSchema`
- Multiple transport layers — stdio for subprocess servers, Streamable HTTP + SSE for networked servers
- Cross-platform — `core` and `stdio` run on JVM, Scala.js, and Scala Native; `http4s` on JVM and Scala.js
- Fluent builder API for servers, tools, resources, and prompts
- Effect-polymorphic, built on Cats Effect
- Zero-boilerplate codec derivation with Scala 3's `derives` syntax

## Where to next

- **[Introduction → MCP feature support](introduction/feature-support.md)** — what's implemented vs. what isn't
- **[Getting Started → Quick start](getting-started/quick-start.md)** — add the dependency and write your first tool
- **Getting Started → [Tools](getting-started/tools.md), [Resources](getting-started/resources.md), [Prompts](getting-started/prompts.md)** — the core building blocks
- **[Getting Started → JSON schema derivation](getting-started/json-schema-derivation.md)** — `derives JsonSchema` and annotations
- **[Getting Started → Server construction](getting-started/server-construction.md)** — Stdio + HTTP transports
- **[Modules](modules/index.md)** — one page per module (core, stdio, http4s, openapi, redis, tapir, golden-munit, explorer)
- **[Tools → OpenAPI MCP Proxy](tools/openapi-mcp-proxy.md)** — turn any OpenAPI API into an MCP server
- **[Testing → Golden testing](testing/golden-testing.md)** — snapshot-test your server's spec
- **[Examples](examples/index.md)** — six runnable example servers covering every transport
