# redis

`mcp-redis` · JVM

Redis-backed implementations of the per-session state primitives used by the
streaming HTTP server: `SessionStore`, `SessionRefs`, `StateRef`, and the
notification sink. Wraps an `McpHttp.streaming` builder via
`McpRedis.configure(...)` so each session's state lives in Redis instead of
in-memory — the right move for multi-replica deployments and when sessions
need to survive process restarts.

```scala
libraryDependencies += "net.andimiller.mcp" %% "mcp-redis" % "0.9.0"
```

The [Chat](../examples/chat.md) example server uses this module end-to-end.
