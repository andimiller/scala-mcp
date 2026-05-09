# Examples

The project ships eight runnable examples — six servers covering every
transport and feature, plus two clients. Pick the page closest to what you're
building.

## Servers

| Example | Transport | Platform | Port | Highlights |
|---------|-----------|----------|------|------------|
| [Dice](dice.md) | Stdio | JVM / JS / Native | — | Tools, resources, prompts; form elicitation |
| [Pomodoro](pomodoro.md) | HTTP + SSE | JVM | 25000 | Per-session state, resource subscriptions, cancellation |
| [DNS](dns.md) | HTTP + SSE | Scala.js (Node.js) | 8053 | Wrapping Node callback APIs into `IO` |
| [Chat](chat.md) | HTTP + SSE | JVM | 27000 | Redis-backed session state and notifications |
| [Shared Notebook](shared-notebook.md) | HTTP + SSE | JVM | 26000 | HTTP Basic auth; per-user contextual tools |
| [RPG Character Creator](rpg-character-creator.md) | HTTP + SSE | JVM | 1974 | Multi-step elicitation wizard |

## Clients

| Example | Transport | Platform | Highlights |
|---------|-----------|----------|------------|
| [CLI client](cli-client.md) | Stdio + HTTP | JVM | Tiny REPL over either transport — list and invoke tools, resources, prompts |
| [LLM harness](harness.md) | Stdio + HTTP | JVM / Native | Multi-server agent driving an OpenAI-compatible chat endpoint; sampling, elicitation, streaming, slash commands |

For a runnable CLI tool that wraps any OpenAPI 3.x API, see
[Tools → OpenAPI MCP Proxy](../tools/openapi-mcp-proxy.md).
