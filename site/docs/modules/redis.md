# redis

`mcp-redis` · JVM

Redis-backed implementations of the per-session state primitives used by the
streaming HTTP server: `SessionStore`, `SessionRefs`, `StateRef`, and the
notification sink. Wraps an `McpHttp.streaming` builder via
`McpRedis.configure(...)` so each session's state lives in Redis instead of
in-memory — the right move for multi-replica deployments and when sessions
need to survive process restarts.

```scala
libraryDependencies += "net.andimiller.mcp" %% "mcp-redis" % "@VERSION@"
```

`McpRedis.configure` returns a function that swaps a streaming builder's
in-memory factories for Redis-backed ones — session store, session refs,
and notification sink. Apply it before any `.stateful` / `.authenticated`
chains and the rest of the builder is unchanged:

```scala mdoc:compile-only
import cats.effect.IO
import com.comcast.ip4s.*
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.pubsub.PubSubCommands
import fs2.Stream
import net.andimiller.mcp.http4s.McpHttp
import net.andimiller.mcp.redis.McpRedis

def withRedis(
    redis: RedisCommands[IO, String, String],
    pubSub: PubSubCommands[IO, [x] =>> Stream[IO, x], String, String]
) =
  McpRedis.configure[IO, Unit, Unit](redis, pubSub)
    .apply(McpHttp.streaming[IO].name("my-server").version("1.0.0").port(port"8080"))
    .serve
    .useForever
```

The [Chat](../examples/chat.md) example server uses this module end-to-end.

## Authenticated sessions across instances

When the builder also calls `.authenticated[U]`, `McpRedis.configure` wires a
Redis-backed `AuthenticatedSessionStore` that persists the user identity bound
at `initialize` time alongside the session marker. This makes two behaviours
hold across replicas:

- **Predicates on reconstructed sessions.** When a request hits an instance
  whose local cache doesn't know the session, the rebuild reads the stored
  user from Redis and materialises the `Server[F]` with that identity — so
  [`.withToolIf`](../getting-started/per-user-tools.md) gates evaluate the
  same way they did on the originating instance.
- **Drift rejection.** Every request's authenticated identity is compared
  against the identity stored in Redis at session-init time. If they differ
  (an admin flag flipped, a role revoked) the request is rejected with
  `403 Forbidden — Credential mismatch`, regardless of which instance handles
  it and regardless of whether the live session is still in that instance's
  cache.

`U` must have `Encoder[U]` and `Decoder[U]` instances in scope at the call to
`.authenticated[U]` — that's what lets the identity round-trip through Redis.
A `derives Encoder.AsObject, Decoder` clause on the user case class is the
common way to satisfy this.
