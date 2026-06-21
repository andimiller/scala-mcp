# stdio

`mcp-stdio` · JVM · Scala.js · Scala Native

stdin/stdout JSON-RPC transport for subprocess-based MCP servers — the format
expected by Claude Desktop and Claude Code's local server config. A server
built with `core` becomes a stdio server by handing it to
`StdioTransport.run`.

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-stdio" % "@VERSION@"
```

`StdioTransport.run` reads JSON-RPC from stdin, dispatches it to your
`Server[F]`, and writes responses back to stdout. The whole serve loop is
one line:

```scala mdoc:compile-only
import cats.effect.{IO, IOApp}
import net.andimiller.mcp.core.logging.NoOpLogging.given
import net.andimiller.mcp.core.server.{Server, ServerBuilder}
import net.andimiller.mcp.stdio.StdioTransport

object MyServer extends IOApp.Simple:
  def server: IO[Server[IO]] =
    ServerBuilder[IO]("my-server", "1.0.0").build

  def run: IO[Unit] = server.flatMap(StdioTransport.run[IO])
```

See [Server construction → Stdio server](../getting-started/server-construction.md#stdio-server)
for the server side, or [Clients → Client construction](../clients/client-construction.md#stdio-client)
for spawning a subprocess server as an `McpClient`.
