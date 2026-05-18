# MCP feature support

Targeting MCP spec **2025-11-25**. The "Client support" column reflects the
state of major clients (Claude Code, Claude Desktop, Cursor, opencode) per
[modelcontextprotocol.io/clients](https://modelcontextprotocol.io/clients) as
of April 2026:

- **Universal** — all four
- **Most** — three of four
- **Some** — one or two
- **Rare** — niche/inconsistent
- **None** — no major client

| Feature                         | scala-mcp | Client support | Notes                                              |
|---------------------------------|-----------|----------------|----------------------------------------------------|
| Tools (`list` / `call`)         | ✅        | Universal      | Includes `structuredContent`                       |
| Resources, subs, templates      | ✅        | Most           | Cursor doesn't expose resources                    |
| Prompts                         | ✅        | Universal      |                                                    |
| Initialize / capabilities       | ✅        | Universal      | Required by spec                                   |
| Ping                            | ✅        | Universal      | Required by spec                                   |
| Logging notifications           | ✅        | Most           | Accepted; rendering varies. `logging/setLevel` not yet |
| Elicitation                     | 🟡        | Some           | Claude Code + Cursor. Form mode ✅, URL mode ❌    |
| Pagination                      | 🟡        | Universal      | Cursors typed; server always returns `nextCursor=None` |
| Cancellation                    | ✅        | Most           | `notifications/cancelled` cancels the in-flight fiber via per-session registry |
| Sessions (Streamable HTTP)      | ✅        | Most           | `Mcp-Session-Id` + auth-aware sessions             |
| `MCP-Protocol-Version` header   | ❌        | Universal      | Clients send it; we don't validate                 |
| Progress (`progressToken`)      | ❌        | Rare           | Few clients emit the token                         |
| `_meta` on requests/responses   | ✅        | Universal      | Builder API on every type; `ToolMiddleware` exposes request `_meta` to handlers |
| Sampling                        | 🟡        | None           | Server→client plumbing via `ServerRequester`; no typed `SamplingClient` helper yet |
| Roots                           | 🟡        | Most           | `ClientHandler` routes `roots/list`; no typed server-side helper. opencode lacks client support |
| Completion                      | ❌        | None           | No client surfaces it                              |
| Tasks (experimental)            | 🟡        | None           | `ToolExecution.taskSupport` advertised; `tasks/list` / `tasks/cancel` not wired |
| Tool/prompt `list_changed`      | ✅        | Universal      | Notifications wired via `NotificationSink`         |
| 2025-11-25 metadata fields      | ✅        | Some           | `title`, `icons`, `annotations`, `execution`, `_meta` on tools; `title`/`icons`/`annotations`/`_meta` on resources; `title`/`icons`/`_meta` on prompts |
