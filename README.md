# Scala 3 MCP Server Library

A Scala 3 library for building [Model Context Protocol (MCP)](https://modelcontextprotocol.io) servers. MCP is a JSON-RPC 2.0 based protocol that allows AI applications like Claude to access tools, resources, and prompts.

## Features

- **Type-Safe Protocol Implementation** — Complete MCP protocol implementation with Scala 3 enums and opaque types
- **Automatic Schema Derivation** — Use `derives JsonSchema` to automatically generate JSON schemas for your tools
- **Multiple Transport Layers** — Stdio for subprocess-based servers, Streamable HTTP + SSE for networked servers
- **Cross-Platform** — Core and stdio modules support JVM, Scala.js, and Scala Native; http4s module supports JVM and Scala.js
- **Fluent Builder API** — Easy-to-use builders for constructing servers, tools, resources, and prompts
- **Effect-Polymorphic** — Built on Cats Effect for composable, functional programming
- **Zero Boilerplate** — Leverage Scala 3's `derives` syntax for automatic codec derivation

## Module Structure

| Module | Platforms | Description |
|--------|-----------|-------------|
| **core** | JVM, JS, Native | Protocol types, server abstraction, schema derivation, JSON codecs |
| **stdio** | JVM, JS, Native | stdin/stdout transport for subprocess-based servers |
| **http4s** | JVM, JS | Streamable HTTP + SSE transport via http4s Ember |
| **openapi** | JVM, JS, Native | OpenAPI 3.x schema model + tool-builder helpers |
| **redis** | JVM | Redis-backed `SessionStore` / `SessionRefs` / `StateRef` / notification sink |
| **tapir** | JVM | Bridge that turns any `sttp.tapir.Schema[A]` into a `JsonSchema[A]` |
| **golden-munit** | JVM, JS, Native | Golden testing framework for MCP server specs (munit) |
| **explorer** | JS | Browser-based UI for exploring and testing MCP servers |
| **openapi-mcp-proxy** | JVM | Standalone CLI that exposes any OpenAPI API as an MCP server |

## Quick Start

```scala
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core"  % "0.9.0",
  "net.andimiller.mcp" %%% "mcp-stdio" % "0.9.0"
)
```

```scala
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*

case class GreetRequest(name: String) derives JsonSchema, Decoder
case class GreetResponse(message: String) derives JsonSchema, Encoder.AsObject

val greetTool =
  tool.name("greet")
    .description("Greet someone by name")
    .in[GreetRequest]
    .out[GreetResponse]
    .run(req => IO.pure(GreetResponse(s"Hello, ${req.name}!")))
```

See the [full quick-start guide](https://andimiller.github.io/scala-mcp/getting-started/quick-start.html) for HTTP and Redis variants.

## Documentation

Full docs: <https://andimiller.github.io/scala-mcp/>

**Introduction**

- [MCP feature support](https://andimiller.github.io/scala-mcp/introduction/feature-support.html) — what's implemented vs. what isn't

**Getting Started**

- [Quick start](https://andimiller.github.io/scala-mcp/getting-started/quick-start.html)
- [Tools](https://andimiller.github.io/scala-mcp/getting-started/tools.html), [Resources](https://andimiller.github.io/scala-mcp/getting-started/resources.html), [Prompts](https://andimiller.github.io/scala-mcp/getting-started/prompts.html) — the core building blocks
- [JSON schema derivation](https://andimiller.github.io/scala-mcp/getting-started/json-schema-derivation.html)
- [Server construction](https://andimiller.github.io/scala-mcp/getting-started/server-construction.html) — Stdio + HTTP transports

**[Modules](https://andimiller.github.io/scala-mcp/modules/)**

- One page per module — [core](https://andimiller.github.io/scala-mcp/modules/core.html), [stdio](https://andimiller.github.io/scala-mcp/modules/stdio.html), [http4s](https://andimiller.github.io/scala-mcp/modules/http4s.html), [openapi](https://andimiller.github.io/scala-mcp/modules/openapi.html), [redis](https://andimiller.github.io/scala-mcp/modules/redis.html), [tapir](https://andimiller.github.io/scala-mcp/modules/tapir.html), [golden-munit](https://andimiller.github.io/scala-mcp/modules/golden-munit.html), [explorer](https://andimiller.github.io/scala-mcp/modules/explorer.html)

**[Tools](https://andimiller.github.io/scala-mcp/tools/)**

- [OpenAPI MCP Proxy](https://andimiller.github.io/scala-mcp/tools/openapi-mcp-proxy.html) — turn any OpenAPI API into an MCP server

**Testing**

- [Golden testing](https://andimiller.github.io/scala-mcp/testing/golden-testing.html) — snapshot-test your server's spec

**[Examples](https://andimiller.github.io/scala-mcp/examples/)**

- One page per server — [Dice](https://andimiller.github.io/scala-mcp/examples/dice.html), [Pomodoro](https://andimiller.github.io/scala-mcp/examples/pomodoro.html), [DNS](https://andimiller.github.io/scala-mcp/examples/dns.html), [Chat](https://andimiller.github.io/scala-mcp/examples/chat.html), [Shared Notebook](https://andimiller.github.io/scala-mcp/examples/shared-notebook.html), [RPG Character Creator](https://andimiller.github.io/scala-mcp/examples/rpg-character-creator.html)

## License

This library and associated code are available under the MIT license, see LICENSE file.

## Contributing

Feel free to make Pull Requests on github, remember to clearly state the purpose of your change and run all tests.
