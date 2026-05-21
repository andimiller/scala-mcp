# Shared Notebook MCP server

`modules/example-shared-notebook-mcp` · HTTP + SSE · JVM · port 26000

A JVM HTTP server demonstrating **HTTP authentication with per-user contextual
tools**. Authenticated users (alice/bob/charlie via Basic Auth) can write,
read, and share notes; the server uses `.authenticated[UserContext](...)` to
extract the current user and pass it as the context to every tool, resource
template, and prompt.

Alice is also flagged as an admin (`UserContext.isAdmin = true`); bob and
charlie are regular users. Two admin-only tools are gated on that flag with
`.withContextualToolIf((u: UserContext) => u.isAdmin)(...)`, so they only
appear in `tools/list` for alice.

- **Tools (all users):** `write_note`, `read_note`, `share_note`, `unshare_note`, `list_my_notes`, `list_shared_notes`
- **Tools (admin only):** `list_all_notes`, `delete_any_note`
- **Resource templates:** `notebook://{username}` and `notebook://{username}/{note_id}` — built with the multi-segment `path` DSL
- **Prompts:** `summarize_notes`, `collaborate_with` (with arguments)

## Build and run

```bash
sbt exampleNotebook/run
# Server starts on http://0.0.0.0:26000
# Use HTTP Basic auth with alice/password123 (admin), bob/password456, or charlie/password789
```

Connect as alice to see eight tools (including the admin pair); connect as
bob to see six. The admin tools are filtered before `tools/list` is
answered — see [Per-user tool visibility](../getting-started/per-user-tools.md).
