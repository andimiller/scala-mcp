# Quick start

This page walks you from an empty sbt project to a runnable MCP server. The
same tool is wired to two transports below — pick stdio for subprocess-based
clients (Claude Desktop, Claude Code) or HTTP for a networked server.

## Adding the dependency

```scala
// For stdio-based servers
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core"  % "0.9.0",
  "net.andimiller.mcp" %%% "mcp-stdio" % "0.9.0"
)

// For HTTP-based servers
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core"   % "0.9.0",
  "net.andimiller.mcp" %%% "mcp-http4s" % "0.9.0"
)

// Optional: Redis-backed session/state for stateful HTTP servers
libraryDependencies += "net.andimiller.mcp" %% "mcp-redis" % "0.9.0"
```

## Defining a tool

A tool needs typed request/response case classes — `derives JsonSchema` plus a
circe codec for each. The fluent `tool` builder gives the schemas + handler
to the server:

```scala
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*

case class GreetRequest(name: String) derives JsonSchema, Decoder
case class GreetResponse(message: String) derives JsonSchema, Encoder.AsObject

val greetTool: Tool.Resolved[IO] =
  tool.name("greet")
    .description("Greet someone by name")
    .in[GreetRequest]
    .out[GreetResponse]
    .run(req => IO.pure(GreetResponse(s"Hello, ${req.name}!")))
```

## Stdio server

A `Server[IO]` built with `ServerBuilder` becomes a stdio server by handing
it to `StdioTransport.run`. This is what Claude Desktop / Claude Code expect
for local MCP servers:

```scala
import cats.effect.{IO, IOApp}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.stdio.StdioTransport

object MyStdioServer extends IOApp.Simple:
  def server: IO[Server[IO]] =
    ServerBuilder[IO]("my-server", "1.0.0")
      .withTool(greetTool)
      .build

  def run: IO[Unit] = server.flatMap(StdioTransport.run[IO])
```

Run it with `sbt run` (or `nativeLink` for a Scala Native binary) and
register the resulting command in your client's MCP config.

## HTTP server

Same tool, wired into `McpHttp.basic[IO]` and served on a port via http4s
Ember. This is what you want for networked clients (Cursor's HTTP transport,
remote agents, multi-user setups):

```scala
import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import net.andimiller.mcp.http4s.McpHttp

object MyHttpServer extends IOApp.Simple:
  def run: IO[Unit] =
    McpHttp.basic[IO]
      .name("my-server").version("1.0.0")
      .port(port"8080")
      .withTool(greetTool)
      .serve
      .useForever
```

Hit it at `http://localhost:8080/mcp`.

## Where to next

The fluent builders shown above (`tool`, `resource`, `resourceTemplate`,
`prompt`, plus their `contextual*` variants) are documented in detail in
[Tools](tools.md), [Resources](resources.md), and [Prompts](prompts.md).
[Server construction](server-construction.md) covers the streaming HTTP
builder with per-session state, authentication, and the embedded Explorer
UI. [Examples](../examples/index.md) has six end-to-end runnable servers
covering every transport.
