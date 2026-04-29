# Tools

Tools are the most-used MCP feature: typed, schema-described functions an AI
client can call. The snippets below are type-checked by mdoc — they share the
imports and placeholder types in the setup block.

```scala mdoc:silent
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.protocol.ToolResult

case class MyRequest(value: String)  derives JsonSchema, Decoder, Encoder.AsObject
case class MyResponse(value: String) derives JsonSchema, Decoder, Encoder.AsObject

trait MyCtx:
  def doSomething(req: MyRequest): IO[MyResponse]
```

## Fluent builder

`.run` on a non-contextual builder erases the input/output types into JSON
schemas and returns a `Tool.Resolved[F]` — the form a `Server` dispatches:

```scala mdoc:silent
val myTool: Tool.Resolved[IO] =
  tool.name("my_tool")
    .description("Tool description")
    .in[MyRequest]
    .out[MyResponse]
    .run(req => IO.pure(MyResponse(req.value)))
```

## Returning `ToolResult` directly

`.runResult` lets you return `ToolResult[Out]` (`Success` / `Text` / `Error`)
directly when the call can fail or wants to short-circuit:

```scala mdoc:silent
val mayFail: Tool.Resolved[IO] =
  tool.name("risky")
    .in[MyRequest]
    .out[MyResponse]
    .runResult(req => IO.pure(ToolResult.Error("nope")))
```

## Contextual tools

A contextual tool receives a per-session context value when called. Use this
for state, auth-derived identity, or per-session resources. The return type
keeps the context, input, and output types so the server can wire it up
later:

```scala mdoc:silent
val ctxTool: Tool[IO, MyCtx, MyRequest, MyResponse] =
  contextualTool[MyCtx]
    .name("my_tool")
    .description("Tool description")
    .in[MyRequest]
    .out[MyResponse]
    .run((ctx, req) => ctx.doSomething(req))
```

`Tool.builder[IO]` and `Tool.contextual[MyCtx]` are equivalent to the `tool`
and `contextualTool` helpers above and remain available.
