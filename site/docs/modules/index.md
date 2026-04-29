# Modules

scala-mcp is a multi-module library — pick the modules you need for the
transports and features your server uses. Core types and the stdio transport
are tiny; HTTP, Redis, and OpenAPI support layer on top.

| Module | Platforms | Description |
|--------|-----------|-------------|
| [core](core.md) | JVM, JS, Native | Protocol types, server abstraction, schema derivation, JSON codecs |
| [stdio](stdio.md) | JVM, JS, Native | stdin/stdout transport for subprocess-based servers |
| [http4s](http4s.md) | JVM, JS | Streamable HTTP + SSE transport via http4s Ember; bundles the Explorer UI |
| [openapi](openapi.md) | JVM, JS, Native | OpenAPI 3.x schema model + tool-builder helpers |
| [redis](redis.md) | JVM | Redis-backed `SessionStore` / `SessionRefs` / `StateRef` / notification sink |
| [tapir](tapir.md) | JVM | Bridge that turns any `sttp.tapir.Schema[A]` into a `JsonSchema[A]` |
| [golden-munit](golden-munit.md) | JVM, JS, Native | Golden testing framework for MCP server specs (munit) |
| [explorer](explorer.md) | JS | Browser-based UI for exploring and testing MCP servers (consumed via `http4s`) |
