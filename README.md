# Scala 3 MCP Server Library

A Scala 3 library for building [Model Context Protocol (MCP)](https://modelcontextprotocol.io) servers. MCP is a JSON-RPC 2.0 based protocol that allows AI applications like Claude to access tools, resources, and prompts.

## Features

- **Type-Safe Protocol Implementation** - Complete MCP protocol implementation with Scala 3 enums and opaque types
- **Automatic Schema Derivation** - Use `derives JsonSchema` to automatically generate JSON schemas for your tools
- **Multiple Transport Layers** - Stdio for subprocess-based servers, Streamable HTTP + SSE for networked servers
- **Cross-Platform** - Core and stdio modules support JVM, Scala.js, and Scala Native; http4s module supports JVM and Scala.js
- **Fluent Builder API** - Easy-to-use builders for constructing servers, tools, resources, and prompts
- **Effect-Polymorphic** - Built on Cats Effect for composable, functional programming
- **Zero Boilerplate** - Leverage Scala 3's `derives` syntax for automatic codec derivation

## Module Structure

| Module | Platforms | Description |
|--------|-----------|-------------|
| **core** | JVM, JS, Native | Protocol types, server abstraction, schema derivation, JSON codecs |
| **stdio** | JVM, JS, Native | stdin/stdout transport for subprocess-based servers |
| **http4s** | JVM, JS | Streamable HTTP + SSE transport via http4s Ember |
| **tapir** | JVM | Tapir endpoint definitions |

## Quick Start

### Adding the Dependency

```scala
// For stdio-based servers
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core" % "0.1.0-SNAPSHOT",
  "net.andimiller.mcp" %%% "mcp-stdio" % "0.1.0-SNAPSHOT"
)

// For HTTP-based servers
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core" % "0.1.0-SNAPSHOT",
  "net.andimiller.mcp" %%% "mcp-http4s" % "0.1.0-SNAPSHOT"
)
```

### Creating a Simple Tool

```scala
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.Tool

// Define your request/response types with automatic derivation
case class GreetRequest(name: String) derives JsonSchema, Decoder
case class GreetResponse(message: String) derives JsonSchema, Encoder.AsObject

val greetTool = Tool.buildNamed[IO, GreetRequest, GreetResponse](
  "greet",
  "Greet someone by name"
) { req =>
  IO.pure(GreetResponse(s"Hello, ${req.name}!"))
}
```

## Examples

The project includes three example MCP servers, each demonstrating a different transport and platform:

| Example | Transport | Platform | Port |
|---------|-----------|----------|------|
| **Dice** | Stdio | JVM / JS / Native | N/A |
| **Pomodoro** | HTTP + SSE | JVM | 25000 |
| **DNS** | HTTP + SSE | Scala.js (Node.js) | 8053 |

---

### Dice MCP Server (`modules/example-dice-mcp`)

A cross-platform stdio MCP server demonstrating tools, resources, and prompts.

- **Tool**: `roll_dice` — roll dice using standard notation (e.g., `2d20 + 5`)
- **Resources**: `dice://rules/standard` (static reference), `dice://history` (recent rolls)
- **Prompt**: `explain_notation` — explains dice notation to the user

Uses `StdioMcpResourceIOApp` for zero-boilerplate stdio server setup.

**Build and run (JVM):**

```bash
sbt exampleDiceJVM/run
```

**Build a Scala Native binary:**

```bash
# Requires clang/llvm (e.g. via nix-shell)
sbt exampleDiceNative/nativeLink
./modules/example-dice-mcp/native/target/scala-3.3.4/example-dice-mcp-out
```

**Configure in Claude Code** (`.mcp.json`):

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

---

### Pomodoro MCP Server (`modules/example-pomodoro-mcp`)

A JVM HTTP server demonstrating dynamic resources with subscription notifications, server-initiated logging, and argument-based prompts.

- **Tools**: `start_timer`, `pause_timer`, `resume_timer`, `stop_timer`, `get_status`
- **Resources**: `pomodoro://status` (subscribable), `pomodoro://history`, `pomodoro://timers/{name}` (template)
- **Prompts**: `plan_session` (with arguments), `review_day`

**Build and run:**

```bash
sbt examplePomodoro/run
# Server starts on http://0.0.0.0:25000
```

**Configure in Claude Code** (`.mcp.json`):

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

---

### DNS MCP Server (`modules/example-dns-mcp`)

A Scala.js server running on Node.js, demonstrating how to wrap Node.js callback-based APIs (`dns` module) into cats-effect `IO` via `IO.async_`. Uses the HTTP transport, which allows concurrent requests — a good fit for the async nature of Node.js.

- **Tools**: `resolve_dns` (A, AAAA, MX, TXT, CNAME, NS records), `reverse_dns` (IP to hostnames)
- **Resource**: `dns://reference/record-types` (static markdown reference)
- **Prompt**: `diagnose_dns` — comprehensive DNS diagnosis for a domain

**Build and run:**

```bash
# Link the Scala.js output
sbt exampleDns/fastLinkJS

# Run on Node.js
node modules/example-dns-mcp/target/scala-3.3.4/example-dns-mcp-fastopt/main.js
# Server starts on http://0.0.0.0:8053
```

**Configure in Claude Code** (`.mcp.json`):

```json
{
  "mcpServers": {
    "dns": {
      "type": "streamable-http",
      "url": "http://localhost:8053/mcp"
    }
  }
}
```

---

## API Overview

### Tool Creation

```scala
// Named helper with explicit name and description
val tool = Tool.buildNamed[IO, MyRequest, MyResponse](
  "my_tool",
  "Tool description"
) { req => IO.pure(MyResponse(...)) }

// Fluent builder
val tool = Tool.builder[IO]
  .name("my_tool")
  .description("Tool description")
  .schemaFrom[MyRequest]
  .handler { (req: MyRequest) => IO.pure(MyResponse(...)) }
```

### Resource Creation

```scala
// Static resource
McpResource.static[IO](
  resourceUri = "file:///config.json",
  resourceName = "Config File",
  content = """{"key": "value"}""",
  resourceMimeType = Some("application/json")
)

// Dynamic resource (re-read on each access)
McpResource.dynamic[IO](
  resourceUri = "app://status",
  resourceName = "Server Status",
  reader = () => IO.pure(s"Status at ${Instant.now}")
)
```

### Prompt Creation

```scala
Prompt.dynamic[IO](
  promptName = "code_review",
  promptDescription = Some("Code review prompt"),
  promptArguments = List(
    PromptArgument("code", Some("Code to review"), required = true)
  ),
  generator = { args =>
    val code = args.get("code").flatMap(_.asString).getOrElse("")
    IO.pure(List(PromptMessage.user(s"Please review this code: $code")))
  }
)
```

### Server Construction

```scala
ServerBuilder[IO]("my-server", "1.0.0")
  .withTools(tool1, tool2)
  .withResources(resource1)
  .withPrompts(prompt1)
  .enableResourceSubscriptions
  .enableLogging
  .build
```

### Convenience Base Traits

For stdio servers, extend `StdioMcpResourceIOApp`:

```scala
object MyServer extends StdioMcpResourceIOApp[MyResources]:
  def serverName    = "my-server"
  def serverVersion = "1.0.0"
  def mkResources   = Resource.eval(...)
  def tools(r: MyResources)     = List(...)
  def resources(r: MyResources) = List(...)
  def prompts(r: MyResources)   = List(...)
```

For HTTP servers, extend `HttpMcpApp`:

```scala
object MyServer extends HttpMcpApp[MyResources]:
  def serverName    = "my-server"
  def serverVersion = "1.0.0"
  override def port = port"9000"
  def mkResources   = Resource.eval(...)
  def tools(r: MyResources, sink: NotificationSink[IO])     = List(...)
  def resources(r: MyResources, sink: NotificationSink[IO]) = List(...)
  def prompts(r: MyResources, sink: NotificationSink[IO])   = List(...)
```

## JSON Schema Derivation

The library provides automatic JSON Schema derivation using Scala 3's `derives` clause, with optional annotations:

```scala
case class SearchRequest(
  @description("The search query")
  query: String,
  @description("Maximum number of results")
  @example(10)
  maxResults: Int = 10,
  filters: Option[List[String]] = None
) derives JsonSchema, Decoder
```

## Building

```bash
# Compile all modules
sbt compile

# Run tests
sbt test

# Link Scala.js examples
sbt exampleDns/fastLinkJS
```

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
