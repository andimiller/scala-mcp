# LLM harness

`modules/example-harness` · Stdio + HTTP · JVM / Native

A minimal LLM ↔ MCP agent. Reads a Claude-style `.mcp.json`, opens every
server it lists, hands those servers' tools to an OpenAI-compatible chat
endpoint, and runs an interactive REPL with streaming responses, slash
commands, and tool-calling.

This is the most complete client example in the repo — it exercises every
moving part: both transports, the `McpClient` API, server-initiated
sampling and elicitation callbacks, and notifications.

## What it does

- **Connects to every MCP server in your `.mcp.json`** — stdio (`command`)
  and HTTP (`type: "http"`, `url`) entries are both supported.
- **Bridges every tool to OpenAI-style tool-calling.** Tool names are
  namespaced as `serverName__toolName` so collisions are unambiguous and
  the LLM picks the server explicitly. Servers that advertise
  `resources` also get synthetic `<server>__list_resources` and
  `<server>__read_resource` tools so the LLM can browse and read MCP
  resources via the same channel.
- **Streams responses token-by-token.** The default `chat/completions`
  endpoint is consumed via SSE; reasoning tokens (DeepSeek / GLM / OpenRouter
  conventions) stream alongside content tokens in a dimmed lane.
- **Handles server callbacks.** `sampling/createMessage` round-trips
  through the same LLM endpoint. `elicitation/create` prompts the user
  field-by-field on the terminal, with type coercion (`integer`,
  `number`, `boolean`).
- **Surfaces notifications.** Server-initiated logs and list-changed
  events print dim alongside the chat output.
- **Slash commands.** `/help`, `/prompts` (lists every connected
  server's prompts), `/prompt <serverName__promptName> [k=v…]` (invokes a
  prompt and continues the chat from its messages). `:q` / `:quit` exits.

## Configuration

The harness reads a Claude-style `.mcp.json`:

```json
{
  "mcpServers": {
    "dice": {
      "command": "sbt",
      "args": ["exampleDiceJVM/run"]
    },
    "pomodoro": {
      "type": "http",
      "url": "http://localhost:25000/mcp"
    }
  }
}
```

Discriminator: a `command` key means stdio; otherwise (or `type: "http"`)
means streamable HTTP. Headers can be passed as `"headers": { ... }` for
HTTP entries.

## Build and run (JVM)

```bash
sbt 'exampleHarnessJVM/run --config .mcp.json --base-url https://api.openai.com/v1 --api-key sk-… --model gpt-4o-mini'
```

Any OpenAI-compatible endpoint works — set `--base-url` to your provider's
URL (Anthropic via OpenRouter, DeepSeek, GLM, a local Ollama, etc.) and
`--model` to a model id that endpoint understands.

## Build and run (Scala Native)

```bash
# Requires clang/llvm and s2n-tls — `nix-shell` provides both.
sbt exampleHarnessNative/nativeLink
./modules/example-harness/native/target/scala-3.3.4/example-harness-out \
  --config .mcp.json --base-url https://api.openai.com/v1 \
  --api-key sk-… --model gpt-4o-mini
```

The native binary is single-file and starts in milliseconds, which makes
it convenient as a long-running terminal companion.

## What it demonstrates

The harness source under
`modules/example-harness/shared/src/main/scala/net/andimiller/mcp/examples/harness/`
is split into focused files worth reading in order:

| File | Shows |
|------|-------|
| `Main.scala` | Wiring: load `.mcp.json`, build the LLM client, build the shared `ClientHandler`, open every server, collect tools and prompts, hand off to `Repl.run`. |
| `McpClients.scala` | One function per `McpServerSpec` that returns a `Resource[F, McpClient[F]]` — both `StdioMcpClient.builder` and `StreamableHttpMcpClient.builder`. |
| `ClientHandlers.scala` | The capability advertisement (`sampling`, `elicitation` with `form`) and dispatch by method name. |
| `SamplingHandler.scala` | `sampling/createMessage` → forward to `OpenAiClient.chat`, shape the response back into the MCP wire format. |
| `ElicitationHandler.scala` | `elicitation/create` → walk the schema's `properties`, prompt the terminal field-by-field, type-coerce, return an `accept` / `cancel` response. |
| `ToolBridge.scala` | Aggregate every server's tools into a single OpenAI-shaped tool list with namespaced names; route tool calls back to the right `McpClient`; synthesise `list_resources` / `read_resource` tools for servers that advertise resources. |
| `PromptBridge.scala` | Surface MCP prompts as `/prompt …` slash commands; convert `PromptMessage` content into OpenAI `ChatMessage`s. |
| `Notifications.scala` | One background fiber per server that drains `client.notifications` and prints them dim. |
| `Repl.scala` | The chat loop: streaming output with separate "content" and "thinking" lanes, tool-call hops bounded by `MaxToolHops`, slash-command dispatch. |
| `OpenAiClient.scala` / `OpenAiTypes.scala` | A tiny OpenAI-compatible chat client (single POST or streaming SSE) plus the wire types it needs. |

Together they're a worked answer to "what does it take to plug an LLM
into an arbitrary set of MCP servers." Most of the protocol-level
plumbing is upstream in `McpClient` and `ClientHandler` — the harness is
mostly bridging code between MCP shapes and OpenAI shapes.
