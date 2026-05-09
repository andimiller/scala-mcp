# explorer

Scala.js · not published

A browser-based UI for interacting with any HTTP MCP server: browse tools,
resources, resource templates, and prompts; call them interactively; inspect
results. Useful for development and debugging without rigging up a real
client.

The Explorer is a Scala.js + [Tyrian](https://tyrian.indigoengine.io/) app
styled with [Bulma](https://bulma.io/), bundled into static assets by Parcel.
You don't add it as a dependency directly — instead, the [http4s](http4s.md)
module bundles the pre-built assets and serves them via `.withExplorer(...)`
on your `McpHttp.basic` / `McpHttp.streaming` builder:

```scala mdoc:compile-only
import cats.effect.IO
import net.andimiller.mcp.http4s.McpHttp

val builder =
  McpHttp.streaming[IO]
    .withExplorer(redirectToRoot = true) // serves at /explorer; redirects / there too
```

See [http4s → Embedded Explorer UI](http4s.md#embedded-explorer-ui) for the
asset-rebuild workflow.
