# Dice MCP server

`modules/example-dice-mcp` · Stdio · JVM / JS / Native

A cross-platform stdio MCP server demonstrating tools, resources, prompts,
and form elicitation.

- **Tools:** `roll_dice` — roll dice using standard notation (e.g., `2d20 + 5`); `roll_interactive` — build a dice expression by repeatedly asking the client for `(face, count)` choices via elicitation
- **Resources:** `dice://rules/standard` (static reference), `dice://history` (recent rolls)
- **Prompt:** `explain_notation` — explains dice notation to the user

Uses `IOApp.Simple`, `ServerBuilder`, and `StdioTransport.run` with a
`SessionContext`-aware factory so each session gets its own `Random`, history
ref, and `ElicitationClient`.

## Build and run (JVM)

```bash
sbt exampleDiceJVM/run
```

## Build a Scala Native binary

```bash
# Requires clang/llvm (e.g. via nix-shell)
sbt exampleDiceNative/nativeLink
./modules/example-dice-mcp/native/target/scala-3.3.4/example-dice-mcp-out
```

> **Note:** the native binary must be re-linked any time the server changes —
> `sbt exampleDiceJVM/run` is the fastest feedback loop during development.

## Configure in Claude Code

`.mcp.json`:

```json
{
  "mcpServers": {
    "dice": {
      "command": "sbt",
      "args": ["exampleDiceJVM/run"]
    }
  }
}
```

Or with a pre-built Native binary:

```json
{
  "mcpServers": {
    "dice": {
      "command": "./modules/example-dice-mcp/native/target/scala-3.3.4/example-dice-mcp-out"
    }
  }
}
```
