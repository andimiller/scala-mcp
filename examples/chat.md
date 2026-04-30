# Chat MCP server

`modules/example-chat-mcp` · HTTP + SSE · JVM · port 27000

A JVM HTTP server demonstrating **Redis-backed session state**. Each connected
session has its own username and current room, and the chat history itself is
shared across sessions through Redis. Resource subscription updates are pushed
when new messages arrive.

- **Tools:** `set_username`, `create_room`, `join_room`, `send_message`, `read_messages`
- **Resources:** `chat://rooms` (list of rooms), `chat://rooms/{room}/messages` (resource template, subscribable)
- **Prompt:** `summarize_chat` — summarises recent messages in the current room
- Built with `McpRedis.configure(...)` wrapping `McpHttp.streaming` so the per-session refs live in Redis instead of in-memory

## Build and run

```bash
# Requires a Redis server on redis://localhost:6379
sbt exampleChat/run
# Server starts on http://0.0.0.0:27000
```
