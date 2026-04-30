# Pomodoro MCP server

`modules/example-pomodoro-mcp` · HTTP + SSE · JVM · port 25000

A JVM HTTP server demonstrating dynamic resources with subscription
notifications, server-initiated logging, argument-based prompts, and
**MCP cancellation**. Uses `McpHttp.streaming` with per-session state —
each client session gets its own `PomodoroTimer` wired to the notification sink.

- **Tools:** `start_timer`, `pause_timer`, `resume_timer`, `stop_timer`, `get_status`, `sleep_blocking` (cancellation demo — its `onCancel` hook reports how long it actually slept)
- **Resources:** `pomodoro://status` (subscribable), `pomodoro://history`, `pomodoro://timers/{name}` (template via `path.static(...) *> path.named(...)`)
- **Prompts:** `plan_session` (with `task` / `session_count` arguments), `review_day`
- **Explorer:** Bundled at `/explorer` with redirect from `/`

## Build and run (in-memory state)

```bash
sbt examplePomodoro/run
# Server starts on http://0.0.0.0:25000
# Explorer UI at http://localhost:25000 (redirects to /explorer/index.html)
```

## Build and run (Redis-backed state, port 25001)

```bash
# Requires a Redis server on redis://localhost:6379
sbt 'examplePomodoro/runMain net.andimiller.mcp.examples.pomodoro.PomodoroMcpServerRedis'
```

## Configure in Claude Code

`.mcp.json`:

```json
{
  "mcpServers": {
    "pomodoro": {
      "type": "streamable-http",
      "url": "http://localhost:25000/mcp"
    }
  }
}
```
