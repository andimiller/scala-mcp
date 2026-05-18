# trace4cats

`mcp-trace4cats` · JVM

A [trace4cats](https://github.com/trace4cats/trace4cats)-backed
`ToolMiddleware` that wraps every `tools/call` in a span. Drop it onto a
server builder, plug in whatever `Trace[F]` instance your app already
uses, and tool calls show up in your tracing backend with built-in
attributes plus anything else you care to extract from the request.

```scala
libraryDependencies += "net.andimiller.mcp" %% "mcp-trace4cats" % "0.11.0"
```

The module depends on `trace4cats-core` only — exporters, samplers, and
the choice of `Trace[F]` instance stay yours.

## Built-in span shape

For each call, the middleware emits a span shaped like:

| field | value |
|-------|-------|
| name  | `mcp.tools.call ${request.name}` |
| `mcp.tool.name`     | the tool's name (string) |
| `mcp.tool.is_error` | response's `isError` flag (boolean) |
| status | `SpanStatus.Ok` on success, `SpanStatus.Internal(...)` if `response.isError` |

## Wiring it up

The no-arg overload wraps tool calls with just the built-in attributes:

```scala
import cats.effect.IO
import net.andimiller.mcp.core.server.ServerBuilder
import net.andimiller.mcp.trace4cats.TraceToolMiddleware
import trace4cats.Trace

def wireUp(using Trace[IO]) =
  ServerBuilder[IO]("my-server", "1.0.0")
    .withToolMiddleware(TraceToolMiddleware[IO, Unit])
    .build
```

`TraceToolMiddleware[IO, Unit]` requires a `Trace[IO]` in scope. Use
whichever trace4cats integration you prefer — `trace4cats-iolocal`, a
Kleisli instance, or anything else that satisfies the typeclass. The
second type parameter is the server's `Ctx` (`Unit` for the
non-contextual `ServerBuilder` / `McpHttp.basic`; the per-session type
for `McpHttp.streaming`).

## Adding custom attributes

The extractor overload takes an effectful function from
`ToolCallContext[F, Ctx]` to a `Map[String, AttributeValue]` of
additional attributes. Returned attributes are recorded INSIDE the span
and BEFORE the handler runs, so they're captured even if the handler
errors. The context gives you the request, the session id (when
available), the per-session `Ctx`, and the resolved tool — so
extractors can reach `ctx.request._meta`, `ctx.sessionId`, `ctx.ctx`, or
`ctx.resolved.annotations`:

```scala
import cats.effect.IO
import net.andimiller.mcp.core.server.{ServerBuilder, ToolCallContext}
import net.andimiller.mcp.trace4cats.TraceToolMiddleware
import trace4cats.AttributeValue
import trace4cats.Trace

def extractor(ctx: ToolCallContext[IO, Unit]): IO[Map[String, AttributeValue]] =
  IO.pure {
    val user = ctx.request.arguments.hcursor.get[String]("user").toOption
    user.map(u => Map("mcp.tool.user" -> AttributeValue.StringValue(u))).getOrElse(Map.empty)
  }

def wireUp(using Trace[IO]) =
  ServerBuilder[IO]("my-server", "1.0.0")
    .withToolMiddleware(TraceToolMiddleware[IO, Unit](extractor))
    .build
```

Built-in attributes the middleware adds for you:

| Key                  | When                                           |
|----------------------|------------------------------------------------|
| `mcp.tool.name`      | always                                         |
| `mcp.session.id`     | when `ctx.sessionId` is `Some` (streaming HTTP) |
| `mcp.tool.is_error`  | after the handler runs                         |

## Composition

Server-wide middleware (`.withToolMiddleware`) wraps per-tool middleware
(`.withMiddleware` on `Tool[F, Ctx]`) which wraps the handler. First
registered = outermost within each level. So stacking the tracing
middleware server-wide alongside a per-tool rate-limiter gets you:

```
request →  TraceToolMiddleware  →  rateLimit (per-tool)  →  handler
response ←  TraceToolMiddleware  ←  rateLimit             ←  handler
```

The span fires around everything — the rate-limiter's latency is part
of the span just like the handler's. See
[tool metadata](../getting-started/tool-metadata.md#middleware-reading-and-decorating-_meta)
for the broader middleware story.

## Continuing an upstream trace

`TraceToolMiddleware` always opens a child of the ambient `Trace[F]`
context — it doesn't read inbound trace context from the request. When
your caller has its own trace running and passes a `traceparent` in
`request._meta`, you want to *continue* that trace so the cross-system
view stitches together. That's what **`EntrypointTraceToolMiddleware`**
is for. It's a separate middleware in the same module.

The shape:

```scala
import cats.effect.IO
import net.andimiller.mcp.core.server.ServerBuilder
import net.andimiller.mcp.trace4cats.EntrypointTraceToolMiddleware
import net.andimiller.mcp.trace4cats.TraceMetaExtractor
import trace4cats.EntryPoint
import trace4cats.Span
import trace4cats.context.Local

def wireUp(ep: EntryPoint[IO], local: Local[IO, Span[IO]]) =
  ServerBuilder[IO]("my-server", "1.0.0")
    .withToolMiddleware(
      EntrypointTraceToolMiddleware[IO, Unit](ep, local, TraceMetaExtractor.w3c[IO, Unit])
    )
    .build
```

Building the `Local[IO, Span[IO]]` is a one-liner with the
`trace4cats-iolocal` module:

```scala
import cats.effect.IOLocal
import trace4cats.iolocal.ioLocalProvide

IOLocal[Span[IO]](rootSpan).map(ioLocalProvide(_))
```

Three dependencies:

1. **`EntryPoint[F]`** — your app-level boundary primitive (constructed
   once from your `SpanSampler` + `SpanCompleter`, exactly like every
   other trace4cats integration).
2. **`Local[F, Span[F]]`** — usually built from an `IOLocal[Span[IO]]`
   via `ioLocalProvide`. Any downstream code using `Trace[F]` (e.g.
   `ioLocalTrace(local)`) will see the new span as the ambient context.
3. **`headers: ToolCallContext[F, Ctx] => F[TraceHeaders]`** — pulls the
   propagation headers out of the request. The included
   `TraceMetaExtractor.w3c[F, Ctx]` pulls W3C `traceparent` and
   `tracestate` from `ctx.request._meta`. Return `TraceHeaders.empty` to
   force a fresh root.

Optionally a fourth: the same `extractor: ToolCallContext[F, Ctx] =>
F[Map[String, AttributeValue]]` shape as the simpler middleware, for
custom attributes.

### Which one to pick?

| Scenario | Use |
|----------|-----|
| Single-process server, internal tool calls, you just want spans recorded | `TraceToolMiddleware` |
| Cross-service traces — caller has its own trace running and sends propagation headers via `_meta` | `EntrypointTraceToolMiddleware` |

The propagation middleware is strictly more capable but takes a few
more dependencies at the wiring site. If you're not sure whether
callers will propagate, you can start with `EntrypointTraceToolMiddleware`
— absent any inbound context, it falls back to a fresh root span just
like `TraceToolMiddleware` would.
