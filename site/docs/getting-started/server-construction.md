# Server construction

Once you have your tools, resources, and prompts, you wire them into a `Server`
and pick a transport. `Stdio` for subprocess-based servers (Claude Desktop,
Claude Code), `Http` for networked servers (Streamable HTTP + SSE).

The setup block below builds a minimal tool / resource / prompt so the
`.withTool / .withResource / .withPrompt` chains compile in isolation.

```scala mdoc:silent
import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.protocol.PromptMessage

case class Req(value: String)  derives JsonSchema, Decoder, Encoder.AsObject
case class Resp(value: String) derives JsonSchema, Decoder, Encoder.AsObject

val myTool: Tool.Resolved[IO] =
  tool.name("greet").in[Req].out[Resp]
    .run(req => IO.pure(Resp(s"Hello, ${req.value}!")))

val staticRes: McpResource[IO, Unit] =
  resource
    .uri("file:///config.json")
    .name("Config File")
    .staticContent[IO]("""{"key": "value"}""")

val staticPrompt: Prompt[IO, Unit] =
  prompt
    .name("explain")
    .messages[IO](List(PromptMessage.user("Explain MCP.")))
```

## Stdio server

```scala mdoc:compile-only
import net.andimiller.mcp.stdio.StdioTransport

object MyServer extends IOApp.Simple:
  def server: IO[Server[IO]] =
    ServerBuilder[IO]("my-server", "1.0.0")
      .withTool(myTool)
      .withResource(staticRes)
      .withPrompt(staticPrompt)
      .build

  def run: IO[Unit] = server.flatMap(StdioTransport.run[IO])
```

`StdioTransport.run` also has a factory overload — `run(ctx => F[Server[F]])` —
that hands you a `SessionContext` so you can wire per-session refs, an
`ElicitationClient`, or a notification sink in.

## HTTP server (basic)

```scala mdoc:compile-only
import net.andimiller.mcp.http4s.McpHttp

val basicServer =
  McpHttp.basic[IO]
    .name("my-server").version("1.0.0")
    .port(port"8080")
    .withTool(myTool)
    .withResource(staticRes)
    .withPrompt(staticPrompt)
    .withExplorer(redirectToRoot = true)
    .serve     // : Resource[IO, http4s.server.Server]
    .useForever
```

## HTTP server (streaming with per-session state)

```scala mdoc:compile-only
import net.andimiller.mcp.http4s.McpHttp

trait MyTimer:
  def start(req: Req): IO[String]
  def status: IO[String]
  def summary: IO[List[PromptMessage]]

object MyTimer:
  def create(sink: Any): IO[MyTimer] = IO(new MyTimer {
    def start(req: Req)                = IO.pure("ok")
    def status: IO[String]             = IO.pure("idle")
    def summary: IO[List[PromptMessage]] = IO.pure(Nil)
  })

val streamingServer =
  McpHttp.streaming[IO]
    .name("my-server").version("1.0.0")
    .port(port"25000")
    .stateful[MyTimer](ctx => MyTimer.create(ctx.sink))
    .withContextualTool(
      contextualTool[MyTimer].name("start").in[Req].out[Resp]
        .run((timer, req) => timer.start(req).map(Resp(_)))
    )
    .withContextualResource(
      contextualResource[MyTimer].uri("app://status").read(_.status)
    )
    .withContextualPrompt(
      contextualPrompt[MyTimer].name("review").generate((timer, _) => timer.summary)
    )
    .withExplorer(redirectToRoot = true)
    .enableResourceSubscriptions
    .enableLogging
    .serve.useForever
```

`.stateful[S](ctx => F[S])` chains, so multiple `stateful` / `authenticated`
calls compose into a tuple-shaped context. See [Examples](../examples/index.md) — the
Shared Notebook server uses `.authenticated` for HTTP Basic auth, and the
Pomodoro server uses `.stateful` for per-session timers.
