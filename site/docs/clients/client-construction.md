# Client construction

`McpClient[F]` owns a transport channel, performs the JSON-RPC `initialize`
handshake, and exposes typed methods for the server's capabilities. This
page covers the two transports — stdio (subprocess) and streamable HTTP —
and the API surface you get once connected.

## Adding the dependency

```scala
// stdio client (spawn a subprocess MCP server)
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core"  % "@VERSION@",
  "net.andimiller.mcp" %%% "mcp-stdio" % "@VERSION@"
)

// streamable HTTP client
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core"   % "@VERSION@",
  "net.andimiller.mcp" %%% "mcp-http4s" % "@VERSION@",
  "org.http4s"         %%% "http4s-ember-client" % "0.23.34"
)
```

The HTTP client takes any `org.http4s.client.Client[F]`; ember-client is the
usual choice on JVM and Native, fetch-client on Scala.js.

## Stdio client

`StdioMcpClient.builder` wraps subprocess spawn + handshake into a single
`Resource[F, McpClient[F]]`. The builder is fluent — `.withCommand`,
`.withArgs`, `.withEnv`, `.withWorkingDirectory`, `.withInfo`,
`.withCapabilities`, `.withHandler` — and `.connect` produces the resource.

```scala mdoc:compile-only
import cats.effect.IO
import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.stdio.StdioMcpClient

val program: IO[Unit] =
  StdioMcpClient
    .builder[IO]
    .withCommand("./my-server-binary")
    .withInfo(Implementation("my-client", "0.1.0"))
    .connect
    .use { client =>
      for
        tools <- client.listTools()
        _     <- IO.println(s"server exposes ${tools.tools.size} tool(s)")
      yield ()
    }
```

`.connect` returns a `Resource[IO, McpClient[IO]]`. Releasing the resource
signals EOF on the child's stdin and stops the message-reader fiber.

If you already have raw `Pipe[F, Byte, Nothing]` / `Stream[F, Byte]` pipes
(e.g. you spawned the process yourself, or you're testing over in-memory
byte queues), drop down to `StdioMcpClient.fromStreams` — it returns an
`UninitializedMcpClient[F]` so you can call `.initialize` on your own
schedule.

## HTTP client

`StreamableHttpMcpClient.builder` takes an http4s `Client[F]` plus the MCP
endpoint URI. Same shape as the stdio builder — fluent setters then
`.connect`. The builder also opens a long-poll SSE `GET` for
server-initiated traffic (turn off with `.withSse(false)` if you only want
request/response over `POST`).

```scala mdoc:compile-only
import cats.effect.IO
import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.http4s.StreamableHttpMcpClient
import org.http4s.Uri
import org.http4s.client.Client

def demo(httpClient: Client[IO]): IO[Unit] =
  StreamableHttpMcpClient
    .builder[IO](httpClient, Uri.unsafeFromString("http://localhost:8080/mcp"))
    .withInfo(Implementation("my-client", "0.1.0"))
    .connect
    .use { client =>
      for
        tools <- client.listTools()
        _     <- IO.println(s"server exposes ${tools.tools.size} tool(s)")
      yield ()
    }
```

A typical end-to-end wiring with ember-client looks like:

```scala
import cats.effect.IO
import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.http4s.StreamableHttpMcpClient
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder

val resource =
  for
    http   <- EmberClientBuilder.default[IO].build
    client <- StreamableHttpMcpClient
                .builder[IO](http, Uri.unsafeFromString("http://localhost:8080/mcp"))
                .withInfo(Implementation("my-client", "0.1.0"))
                .connect
  yield client
```

The builder captures the `Mcp-Session-Id` header from the initialize
response and threads it onto every subsequent request. On resource release
it sends an HTTP `DELETE` to terminate the session so server-side state is
freed.

To pass auth headers (bearer tokens, etc.), use `.withHeaders(Headers(...))`
— they're merged into every request the client sends.

## Using the client

`McpClient[F]` exposes the negotiated values as plain fields (no effects,
no `Option`s — the existence of an `McpClient` is proof the handshake
succeeded):

```scala mdoc:compile-only
import cats.effect.IO
import net.andimiller.mcp.core.client.McpClient

def show(client: McpClient[IO]): IO[Unit] =
  IO.println(s"connected to ${client.serverInfo.name} v${client.serverInfo.version}") *>
    IO.println(s"protocol: ${client.protocolVersion}") *>
    IO.println(s"tools? ${client.serverCapabilities.tools.isDefined}")
```

The capability-driven methods cover every server-side feature:

```scala mdoc:compile-only
import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import net.andimiller.mcp.core.client.McpClient

def exercise(client: McpClient[IO]): IO[Unit] =
  for
    // Tools
    tools  <- client.listTools()
    result <- client.callTool("greet", Json.obj("name" -> "world".asJson))

    // Resources
    rs    <- client.listResources()
    body  <- client.readResource("file:///config.json")
    _     <- client.subscribe("file:///config.json")   // updates flow via .notifications
    tmpls <- client.listResourceTemplates()

    // Prompts
    prompts <- client.listPrompts()
    msgs    <- client.getPrompt("explain_notation", Map("topic" -> "dice".asJson))

    // Liveness
    _ <- client.ping()
  yield ()
```

Failures from the server (a tool that returned an error response, a
non-existent resource URI) surface as `ClientSession.McpRemoteException`,
which carries the JSON-RPC error code and message. Decode failures (the
server returned JSON that didn't match the protocol shape) surface as
`ClientSession.McpDecodeException` with the offending body attached.

## Notifications

Servers can push notifications at any time —
`notifications/tools/list_changed`, `notifications/resources/updated`, log
events, and so on. `McpClient.notifications` is an `fs2.Stream` that
multicasts every notification to every subscriber:

```scala mdoc:compile-only
import cats.effect.IO
import net.andimiller.mcp.core.client.McpClient

def watchTools(client: McpClient[IO]): IO[Unit] =
  client.notifications
    .filter(_.method == "notifications/tools/list_changed")
    .evalMap(_ => client.listTools().flatMap(t => IO.println(s"now ${t.tools.size} tool(s)")))
    .compile
    .drain
```

Each `subscribe` allocates an independent buffer (size 64 by default; tune
via `ClientSession.Config.notificationBufferSize` if you build a
`ClientSession` directly).

## Where to next

- **[Client handlers](client-handlers.md)** — respond to server-initiated
  requests (sampling, elicitation, roots) and advertise matching
  capabilities.
- **[Examples → CLI client](../examples/cli-client.md)** — runnable REPL
  over either transport.
- **[Examples → LLM harness](../examples/harness.md)** — multi-server
  agent driving an OpenAI-compatible chat endpoint.
