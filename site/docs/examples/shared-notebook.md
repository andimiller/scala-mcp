# Shared Notebook MCP server

`modules/example-shared-notebook-mcp` · HTTP + SSE · JVM · port 26000

A JVM HTTP server demonstrating **HTTP authentication with per-user contextual
tools**. Authenticated users (alice/bob/charlie via Basic Auth) can write,
read, and share notes; the server uses `.authenticated[UserContext](...)` to
extract the current user and pass it as the context to every tool, resource
template, and prompt.

- **Tools:** `write_note`, `read_note`, `share_note`, `unshare_note`, `list_my_notes`, `list_shared_notes`
- **Resource templates:** `notebook://{username}` and `notebook://{username}/{note_id}` — built with the multi-segment `path` DSL
- **Prompts:** `summarize_notes`, `collaborate_with` (with arguments)

## Build and run

```bash
sbt exampleNotebook/run
# Server starts on http://0.0.0.0:26000
# Use HTTP Basic auth with alice/password123, bob/password456, or charlie/password789
```
