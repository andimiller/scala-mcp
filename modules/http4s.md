# http4s

`mcp-http4s` · JVM · Scala.js

Streamable HTTP + SSE transport via http4s Ember. Two builder entry points:

- `McpHttp.basic[IO]` — plain request/response MCP over HTTP
- `McpHttp.streaming[IO]` — adds session management, resource subscriptions, server-initiated logging, cancellation, and per-session state via `.stateful` / `.authenticated` chains

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-http4s" % "0.9.0"
```

See [Server construction → HTTP server](../getting-started/server-construction.md#http-server-basic)
for the builder chain.

## Embedded Explorer UI

The `http4s` module bundles the [Explorer](explorer.md) — a Scala.js + Tyrian
browser UI for exploring tools, resources, templates, and prompts on any HTTP
MCP server. Enable it on your server with `.withExplorer(...)`:

```scala
import cats.effect.IO
import net.andimiller.mcp.http4s.McpHttp

val builder =
  McpHttp.streaming[IO]
    .withExplorer(redirectToRoot = true) // serves at /explorer, optionally redirects / there
```

The Explorer is served at `/explorer/index.html` and defaults the connection
URL to the current origin + `/mcp`.

The Explorer assets are pre-built into the http4s module's classpath
resources. To rebuild after changes to the Explorer source:

```bash
sbt buildExplorer
```

This compiles the Scala.js app and runs Parcel to bundle the JS and CSS into
`modules/explorer/dist/`, which is then copied into the http4s classpath
resources.
