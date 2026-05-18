# Tool metadata

Beyond `name`, `description`, and `inputSchema`/`outputSchema`, the
2025-11-25 MCP spec gives tools a layer of optional metadata clients use
for UI affordances, behavioural hints, and arbitrary extensions. This
page walks through every field and shows how to set it via the fluent
builder. Snippets are type-checked by mdoc.

```scala
import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, JsonObject}
import io.circe.syntax.*
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*

case class DeleteRequest(id: String)   derives JsonSchema, Decoder
case class DeleteResponse(ok: Boolean) derives JsonSchema, Encoder.AsObject

def doDelete(id: String): IO[DeleteResponse] = IO.pure(DeleteResponse(true))
```

## `title`

A short human-readable label clients show in their UI. Per spec, the
display-name precedence is `Tool.title` > `annotations.title` >
`Tool.name`.

```scala
val titled =
  tool.name("delete_user")
    .title("Delete user")
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

## `description`

The natural-language explanation clients (and LLMs) read to decide when
to call the tool.

```scala
val described =
  tool.name("delete_user")
    .description("Permanently remove a user account by id. This cannot be undone.")
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

## `icons`

Visual representations of the tool. Clients pick the best fit by mime
type and size. Per spec, clients MUST handle `image/png` and
`image/jpeg`, SHOULD handle `image/svg+xml` and `image/webp`. Call
`.icon(...)` to append, or `.icons(List(...))` to replace.

```scala
val iconed =
  tool.name("delete_user")
    .icon(Icon.png("https://example.com/icons/delete.png", sizes = List("48x48", "any")))
    .icon(Icon.svg("https://example.com/icons/delete.svg"))
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

`Icon` smart constructors:

| Constructor                       | What it does                                        |
|-----------------------------------|-----------------------------------------------------|
| `Icon.png(src, sizes)`            | sets `mimeType = image/png`                         |
| `Icon.jpeg(src, sizes)`           | sets `mimeType = image/jpeg`                        |
| `Icon.svg(src, sizes)`            | sets `mimeType = image/svg+xml`                     |
| `Icon.dataUri(mimeType, base64)`  | wraps a pre-base64 string in a `data:` URI          |
| `Icon.dataEncode(mimeType, raw)`  | base64-encodes the raw input for you                |
| `Icon(src, mimeType, sizes, theme)` | full constructor                                  |

`dataEncode` is handy for inlining small icons so the server is
self-contained — no static-asset hosting required:

```scala
val tomato = Icon.dataEncode("image/svg+xml", "<svg>...</svg>")
```

## `annotations` — `ToolAnnotations`

Behavioural hints clients use to decide how to surface the tool. Four
booleans, each with a spec default:

| Hint              | Spec default | Meaning when `true`                       |
|-------------------|--------------|-------------------------------------------|
| `readOnlyHint`    | `false`      | the tool does not modify state            |
| `destructiveHint` | `true`       | the tool may delete or replace data       |
| `idempotentHint`  | `false`      | safe to call with the same args twice     |
| `openWorldHint`   | `true`       | reaches into external/time-dependent systems |

### Declaring hints via `Hint` (recommended)

The snappiest way is the varargs setter, which takes `ToolAnnotations.Hint`
values directly. Each `Hint` case represents one declaration; the builder
folds them into a single `ToolAnnotations`:

```scala
import ToolAnnotations.Hint

val annotated =
  tool.name("delete_user")
    .annotations(Hint.Destroy, Hint.ClosedWorld)
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

The eight cases:

| Case             | Declares                                       |
|------------------|------------------------------------------------|
| `Hint.Read`      | `readOnlyHint=true`, `destructiveHint=false`   |
| `Hint.Write`     | `readOnlyHint=false`, `destructiveHint=false`  |
| `Hint.Destroy`   | `readOnlyHint=false`, `destructiveHint=true`   |
| `Hint.Idempotent`| `idempotentHint=true`                          |
| `Hint.NotIdempotent`| `idempotentHint=false`                      |
| `Hint.OpenWorld` | `openWorldHint=true`                           |
| `Hint.ClosedWorld`| `openWorldHint=false`                         |
| `Hint.Title(s)`  | `title=Some(s)`                                |

### Conflict resolution

When two hints touch the same axis, **`None` always loses to a declared
value; when both sides declare and disagree, the spec default wins**. So
`Hint.Read, Hint.Destroy` resolves to the destroy-shaped output (because
`destructiveHint=true` is the spec default for that field). The rule is
commutative and associative, with `ToolAnnotations()` as identity.

The same rule applies whether you stack via `Hint`s or via the lower-level
`|+|` combinator on `ToolAnnotations` values directly.

### Advanced: composing `ToolAnnotations` directly

If you need to build up a value programmatically — sharing across many
tools, calling `.withDefaults`, branching on runtime state — fall back to
the preset vals and the `Monoid[ToolAnnotations]` instance. Same six named
presets (`read`/`write`/`destroy`/`idempotent`/`closedWorld`/`openWorld`)
that back the `Hint` cases, plus `|+|` from `cats.syntax.semigroup.*`:

```scala
val advanced =
  tool.name("delete_user")
    .annotations {
      import ToolAnnotations.*
      destroy |+| closedWorld
    }
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

Worked examples of conflict resolution:

- `read |+| destroy` → `destroy`-shaped output (destructive's `true`
  wins on `destructiveHint`; read-only's `true` loses to `false` on
  `readOnlyHint`).
- `closedWorld |+| openWorld` → `openWorld`-shaped output (the spec
  default `true` wins).

Contradictory presets resolve toward the safer fallback the client would
use anyway.

### Materialising defaults — `.withDefaults`

Some clients want every hint present on the wire (no `null`/missing).
`.withDefaults` fills any unset field with its spec default:

```scala
val fullySpecified =
  tool.name("delete_user")
    .annotations {
      import ToolAnnotations.*
      (destroy |+| closedWorld).withDefaults
    }
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

`ToolAnnotations.defaults` is the same value materialised on its own —
useful when you want exactly the spec defaults and nothing more. Don't
stack `defaults` via `|+|` though: under the conflict-resolution rule it
would always win, undoing every declared preset. `.withDefaults` is the
right tool to fill in gaps.

## `execution` — `ToolExecution`

Hint whether the tool wants to participate in task-augmented (long-
running, pollable) execution. New in 2025-11-25.

```scala
val tasky =
  tool.name("long_running")
    .execution(ToolExecution.taskOptional)
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

Three values:

- `ToolExecution.taskForbidden` — never run as a task (the spec default)
- `ToolExecution.taskOptional`  — client may run as a task
- `ToolExecution.taskRequired`  — always run as a task

This advertises intent; the matching `tasks/list` / `tasks/cancel`
negotiation isn't wired up in the library yet.

## `_meta` — arbitrary extension metadata

Any tool can carry an `_meta: JsonObject` for non-spec extension fields.
Per spec, key naming convention is `[prefix]name` where prefix is reverse-DNS
(e.g. `com.example.acme/audit-tier`); any prefix whose second label is
`modelcontextprotocol` or `mcp` is reserved for spec use.

Set keys individually:

```scala
val withMeta =
  tool.name("delete_user")
    .meta("com.example.acme/audit-tier", "high".asJson)
    .meta("com.example.acme/owner", "platform".asJson)
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

Or pass a `JsonObject` directly to replace any previous value:

```scala
val replaced =
  tool.name("delete_user")
    .meta(JsonObject("com.example.acme/tag" -> "alpha".asJson))
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

## Sharing common metadata with `Metadata`

When several tools share cross-cutting fields (typically `title`,
`icons`, `_meta`), use the `Metadata` aggregate to build the bundle once
and apply via `.metadata(...)`:

```scala
val brand = Metadata.empty
  .icon(Icon.svg("https://example.com/brand.svg"))
  .meta("com.example.acme/team", "platform".asJson)

val first =
  tool.name("first").metadata(brand)
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))

val second =
  tool.name("second").metadata(brand)
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

`.metadata(brand)` overwrites whatever `title`/`icons`/`_meta` is
currently on the builder; subsequent flat setters override again
(last-write-wins). Per-entity fields (`annotations`, `execution`) stay
on flat setters since they aren't usefully shareable across many tools.

## Middleware: reading and decorating `_meta`

Static `_meta` declared at builder time is one half of the story; the
other half is *dynamic* `_meta` — pulling values from the request,
attaching audit trails to the response, propagating tracing IDs. The
library exposes a `ToolMiddleware[F, Ctx]` type for exactly this:

```scala
// case class ToolCallContext[F, Ctx](
//     request: CallToolRequest,
//     sessionId: Option[String],   // present for session-aware transports
//     resolved: Tool[F, Ctx],      // the tool being called
//     ctx: Ctx                     // per-session context; Unit for stdio / basic HTTP
// )
// type ToolHandler[F, Ctx]    = ToolCallContext[F, Ctx] => F[CallToolResponse]
// type ToolMiddleware[F, Ctx] = ToolHandler[F, Ctx] => ToolHandler[F, Ctx]

val audit: ToolMiddleware[IO, Unit] = handler => ctx =>
  for
    resp <- handler(ctx)
    // Pull progressToken (or anything else) from the incoming request:
    token = ctx.request._meta.flatMap(_("io.modelcontextprotocol/progressToken")).map(_.noSpaces)
    audited = resp.copy(_meta = Some(JsonObject(
      "com.example.audit/tool"    -> ctx.request.name.asJson,
      "com.example.audit/session" -> ctx.sessionId.asJson,
      "com.example.audit/token"   -> token.asJson
    )))
  yield audited
```

The middleware sees:

- `ctx.request` — the full `CallToolRequest` (name, arguments, `_meta`)
- `ctx.sessionId` — `Option[String]`; `Some` under streaming HTTP, `None` for stdio
- `ctx.resolved` — the `Tool[F, Ctx]` being invoked, with all its
  metadata (`title`, `annotations`, `execution`, `_meta`)
- `ctx.ctx` — the per-session `Ctx` value (e.g. the user, tenant, or
  whatever each `.stateful`/`.authenticated` step added). `Unit` for stdio
  and non-streaming HTTP, so non-contextual servers use
  `ToolMiddleware[F, Unit]`.

### Where to attach it

Two scopes, both useful:

- **Server-wide** — `ServerBuilder[IO].withToolMiddleware(audit)`,
  `McpHttp.streaming[IO].withToolMiddleware(audit)`, or
  `McpHttp.basic[IO].withToolMiddleware(audit)`. Applied around every
  tool call. Use for cross-cutting concerns.

- **Per-tool** — `.withMiddleware(audit)` on a `Tool[F, Ctx]`. Use for
  tool-specific logic.

```scala
val rateLimit: ToolMiddleware[IO, Unit] = handler => ctx =>
  // ... rate-limit logic, then ...
  handler(ctx)

val tool1 =
  tool.name("delete_user")
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
    .withMiddleware(rateLimit) // applied per-tool
```

### Composition order

Both layers compose into a single onion around the handler:

```
server.withToolMiddleware(a).withToolMiddleware(b)
  + tool.withMiddleware(c).withMiddleware(d)
  + handler

# request flow:  a → b → c → d → handler
# response flow: handler → d → c → b → a
```

First registered = outermost within each level; server-wide layer wraps
the per-tool layer. The composition rule is fixed.

### Reading `_meta` from the request

`CallToolRequest._meta` is the spec's per-message extension point on
the request side. The well-known `progressToken` key lives there too —
middleware that wants to honour it can pull from
`ctx.request._meta.flatMap(_("io.modelcontextprotocol/progressToken"))`.

Note: `_meta` on the request and `_meta` on the response are
**independent slots** — the spec does not mandate echoing one into the
other. Middleware can choose to do so explicitly, like the `audit`
example above.

## A complete example

Putting it all together — a tool with the full metadata surface:

```scala
val fully =
  tool.name("delete_user")
    .title("Delete user")
    .description("Permanently remove a user account by id.")
    .icon(Icon.png("https://example.com/icons/delete.png", sizes = List("48x48", "any")))
    .annotations(Hint.Destroy, Hint.ClosedWorld)
    .execution(ToolExecution.taskOptional)
    .meta("com.example.acme/audit-tier", "high".asJson)
    .in[DeleteRequest].out[DeleteResponse]
    .run(req => doDelete(req.id))
```

## Where else this applies

Resources, resource templates, prompts, and the server's `Implementation`
all carry the same family of optional metadata fields. The builders for
those entities expose the same setter family (`.title`, `.icon`,
`.annotations`, `.metadata`, `.meta(...)`). The exact subset varies per
entity — resources also support `.size(bytes)`, prompts don't carry
behavioural annotations, etc.
