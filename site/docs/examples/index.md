# Examples

The project ships six example MCP servers, each demonstrating a different
transport, platform, or server feature. Each has its own page below — pick the
one closest to what you're building.

| Example | Transport | Platform | Port | Highlights |
|---------|-----------|----------|------|------------|
| [Dice](dice.md) | Stdio | JVM / JS / Native | — | Tools, resources, prompts; form elicitation |
| [Pomodoro](pomodoro.md) | HTTP + SSE | JVM | 25000 | Per-session state, resource subscriptions, cancellation |
| [DNS](dns.md) | HTTP + SSE | Scala.js (Node.js) | 8053 | Wrapping Node callback APIs into `IO` |
| [Chat](chat.md) | HTTP + SSE | JVM | 27000 | Redis-backed session state and notifications |
| [Shared Notebook](shared-notebook.md) | HTTP + SSE | JVM | 26000 | HTTP Basic auth; per-user contextual tools |
| [RPG Character Creator](rpg-character-creator.md) | HTTP + SSE | JVM | 1974 | Multi-step elicitation wizard |

For a runnable CLI tool that wraps any OpenAPI 3.x API, see
[Tools → OpenAPI MCP Proxy](../tools/openapi-mcp-proxy.md).
