# redis

`mcp-redis` · JVM

Redis-backed implementations of the per-session state primitives used by the
streaming HTTP server: `SessionStore`, `SessionRefs`, `StateRef`, and the
notification sink. Wraps an `McpHttp.streaming` builder via
`McpRedis.configure(...)` so each session's state lives in Redis instead of
in-memory — the right move for multi-replica deployments and when sessions
need to survive process restarts.

```scala
libraryDependencies += "net.andimiller.mcp" %% "mcp-redis" % "0.10.0"
```

`McpRedis.configure` returns a function that swaps a streaming builder's
in-memory factories for Redis-backed ones — session store, session refs,
and notification sink. Apply it before any `.stateful` / `.authenticated`
chains and the rest of the builder is unchanged:

```scala
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
  McpRedis.configure[IO, Unit](redis, pubSub)
    .apply(McpHttp.streaming[IO].name("my-server").version("1.0.0").port(port"8080"))
    .serve
    .useForever
```

The [Chat](../examples/chat.md) example server uses this module end-to-end.
