# Per-user tool visibility

When a `StreamingMcpHttpBuilder` is `.authenticated[U]`, the initial set of
tools advertised to each session can be filtered by the authenticated user.
`.withToolIf` / `.withToolIfF` and their contextual counterparts gate a
tool's registration on a predicate over `U` — admins see one surface,
regular users see another, and tools that get filtered out behave exactly
like tools that don't exist.

This is the *initial* tool set per session, decided at session init. It is
not `tools/list_changed` mid-session — MCP client support for that is too
patchy today.

```scala mdoc:silent
import cats.Eq
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.McpHttp
import org.http4s.{Request, Response, Status}

case class User(name: String, isAdmin: Boolean) derives Encoder.AsObject, Decoder
object User:
  given Eq[User] = Eq.fromUniversalEquals

case class Req()              derives JsonSchema, Decoder
case class Resp(text: String) derives JsonSchema, Decoder, Encoder.AsObject

// Any extractor returning F[Option[U]] works — here a stub that always rejects.
def extract(req: Request[IO]): IO[Option[User]] = IO.pure(None)
val onUnauthorized: Response[IO] = Response[IO](Status.Unauthorized)
```

`.authenticated[U]` requires `Eq[U]`, `Encoder[U]`, and `Decoder[U]`. The codecs
let stores like [Redis](../modules/redis.md) persist the authenticated identity
so multi-instance deployments can validate sessions across processes. For
in-memory deployments the codecs are unused at runtime, but they must still be
in scope — circe-generic `derives` clauses or hand-rolled givens are both fine.

## `.withContextualToolIf` — gate on a pure predicate

The common case: a contextual tool whose visibility depends on the
authenticated user. The predicate receives the same `U` that
`.authenticated[U]` extracted; `Ctx` inside the handler still carries the
user (and any `.stateful` state) so the handler can read it.

```scala mdoc:silent
val publicTool =
  contextualTool[User].name("status").in[Req].out[Resp]
    .run((user, _) => IO.pure(Resp(s"hi ${user.name}")))

val adminOnlyTool =
  contextualTool[User].name("reset").in[Req].out[Resp]
    .run((user, _) => IO.pure(Resp(s"reset by ${user.name}")))

val builder =
  McpHttp.streaming[IO]
    .name("admin-server").version("1.0.0")
    .authenticated[User](extract, onUnauthorized)
    .withContextualTool(publicTool)
    .withContextualToolIf((u: User) => u.isAdmin)(adminOnlyTool)
```

Alice (admin) sees both `status` and `reset` in `tools/list`. Bob
(non-admin) sees only `status`.

## `.withContextualToolIfF` — gate on an effectful predicate

If the visibility check needs I/O (database lookup, feature-flag service,
etc.), use the `F`-suffixed overload. The predicate is run once per session
at init time.

```scala mdoc:silent
def canSeeBeta(u: User): IO[Boolean] = IO.pure(u.name.startsWith("a"))

val betaBuilder =
  McpHttp.streaming[IO]
    .name("beta-server").version("1.0.0")
    .authenticated[User](extract, onUnauthorized)
    .withContextualToolIfF((u: User) => canSeeBeta(u))(adminOnlyTool)
```

## Non-contextual variants

`.withToolIf` / `.withToolIfF` exist for tools that don't need the user
inside their handler — same predicate shape, same compile-time guard. In
practice, gated tools usually want to read the user (for auditing or
permission-narrowing), so `.withContextualToolIf` is the more common
choice.

## Hidden tools and the `.authenticated[U]` requirement

`.withToolIf*` is only callable after `.authenticated[U]`. The builder
encodes this with a `NotGiven[A =:= Unit]` evidence parameter — if you call
the method on an unauthenticated builder it's a compile error, since there
is no auth user for the predicate to evaluate against.

When a user with insufficient permissions calls a tool that's been gated
away, the server responds with the same error path as a tool that doesn't
exist (`-32603` "Tool not found: ..."). There's no distinct "forbidden"
error — clients can't distinguish "you don't have access to this tool"
from "this tool was never registered."

## Worked example

The `SharedNotebookMcpServer` example gates two admin-only tools
(`list_all_notes`, `delete_any_note`) on `UserContext.isAdmin`. Connect as
alice (admin) to see eight tools; connect as bob to see six. See
[Shared Notebook](../examples/shared-notebook.md) for the full setup.
