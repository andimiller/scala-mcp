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
| **explorer** | JS | Browser-based UI for exploring and testing MCP servers |
| **openapi-mcp-proxy** | JVM | Tool to expose OpenAPI APIs as MCP servers |

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

A JVM HTTP server demonstrating dynamic resources with subscription notifications, server-initiated logging, and argument-based prompts. Uses `HttpMcpStatefulResourceApp` with per-session state — each client session gets its own `PomodoroTimer` wired to the notification sink.

- **Tools**: `start_timer`, `pause_timer`, `resume_timer`, `stop_timer`, `get_status`
- **Resources**: `pomodoro://status` (subscribable), `pomodoro://history`, `pomodoro://timers/{name}` (template)
- **Prompts**: `plan_session` (with arguments), `review_day`
- **Explorer**: Bundled at `/explorer` with redirect from `/`

**Build and run:**

```bash
sbt examplePomodoro/run
# Server starts on http://0.0.0.0:25000
# Explorer UI at http://localhost:25000 (redirects to /explorer/index.html)
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

### OpenAPI MCP Proxy (`modules/openapi-mcp-proxy`)

Expose any OpenAPI-compliant REST API as an MCP server. This tool converts OpenAPI operations into MCP tools that AI agents can call.

- **Automatic Tool Generation** - Converts OpenAPI operations into MCP tools with proper JSON schemas
- **Multi-Agent Support** - Works with Claude Desktop, Cursor, and OpenCode via automatic config generation
- **Interactive Management** - Interactive shell for browsing and selecting API operations
- **Flexible Input** - Load specs from URLs or local files (JSON or YAML)

**Build:**

```bash
sbt openapiMcpProxy/assembly
# Creates ./openapi-mcp-proxy.jar
```

**Usage:**

```bash
# List available operations
openapi-mcp-proxy list https://api.example.com/openapi.json

# Register with your agent
openapi-mcp-proxy mcp add --agent claude-desktop https://api.example.com/openapi.json listUsers getUserById

# Or add all operations
openapi-mcp-proxy mcp add --agent opencode https://api.example.com/openapi.json '*'

# Interactive mode
openapi-mcp-proxy mcp add --agent cursor https://api.example.com/openapi.json
```

See the [full documentation](#openapi-mcp-proxy-details) below for more details.

---

## MCP Explorer

The **Explorer** is a browser-based UI for interacting with any HTTP MCP server. It lets you browse tools, resources, resource templates, and prompts, call them interactively, and inspect results — useful for development and debugging.

The Explorer is a Scala.js + [Tyrian](https://tyrian.indigoengine.io/) app styled with [Bulma](https://bulma.io/), bundled into static assets by Parcel. HTTP servers can serve it alongside their MCP endpoint.

### Enabling the Explorer

Any `HttpMcpApp` or `HttpMcpStatefulResourceApp` server can serve the Explorer by setting:

```scala
override def explorerEnabled = true
override def rootRedirectToExplorer = true  // optional: redirect / to /explorer/index.html
```

The Explorer is served at `/explorer/index.html` and defaults the connection URL to the current origin + `/mcp`.

### Building the Explorer

The Explorer assets are pre-built into the http4s module's resources. To rebuild after changes:

```bash
sbt buildExplorer
```

This compiles the Scala.js app and runs Parcel to bundle the JS and CSS into `modules/explorer/dist/`, which is then copied into the http4s classpath resources.

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
  override def explorerEnabled = true
  def mkResources   = Resource.eval(...)
  def tools(r: MyResources, sink: NotificationSink[IO])     = List(...)
  def resources(r: MyResources, sink: NotificationSink[IO]) = List(...)
  def prompts(r: MyResources, sink: NotificationSink[IO])   = List(...)
```

For HTTP servers that need per-session state (e.g. a timer or cache created from the notification sink), extend `HttpMcpStatefulResourceApp`:

```scala
object MyServer extends HttpMcpStatefulResourceApp[SharedState, SessionState]:
  def serverName    = "my-server"
  def serverVersion = "1.0.0"
  def mkResources   = Resource.eval(...)  // shared state, created once
  def mkSessionResources(r: SharedState, sink: NotificationSink[IO]) =
    SessionState.create(sink)             // per-session state
  def tools(r: SharedState, s: SessionState)     = List(...)
  def resources(r: SharedState, s: SessionState) = List(...)
  def prompts(r: SharedState, s: SessionState)   = List(...)
```

## JSON Schema Derivation

The library provides automatic JSON Schema derivation using Scala 3's `derives` clause, powered by the [sttp-apispec](https://github.com/softwaremill/sttp-apispec) `Schema` type. Annotations allow adding descriptions and examples:

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

---

## OpenAPI MCP Proxy Details

The OpenAPI MCP Proxy tool converts any OpenAPI-compliant REST API into an MCP server that AI agents can interact with.

### Features

- **Automatic Tool Generation** - Converts OpenAPI operations into MCP tools with proper JSON schemas
- **Multi-Agent Support** - Works with Claude Desktop, Cursor, and OpenCode via automatic config generation
- **Interactive Management** - Interactive shell for browsing and selecting API operations
- **Flexible Input** - Load specs from URLs (`https://api.example.com/openapi.json`) or local files
- **Full HTTP Support** - Handles GET, POST, PUT, DELETE, PATCH with path params, query params, headers, and request bodies

### Installation

```bash
# Build the executable JAR
sbt openapiMcpProxy/assembly

# The JAR is created as ./openapi-mcp-proxy.jar
# Make it executable and add to your PATH
chmod +x openapi-mcp-proxy.jar
mv openapi-mcp-proxy.jar /usr/local/bin/openapi-mcp-proxy
```

### Commands

| Command | Description |
|---------|-------------|
| `list <spec>` | List all available operationIds with their HTTP method and path |
| `proxy <spec> <operationIds...>` | Run the MCP stdio proxy for selected operations |
| `mcp add [--agent] <spec> [operationIds...]` | Register operations in agent config |
| `mcp del [--agent] <name>` | Remove a server entry from config |
| `mcp manage [--agent]` | Interactive shell for managing config entries |

**Supported Agents:**
- `claude` - Claude Code (`.mcp.json`)
- `claude-desktop` - Claude Desktop app (macOS only)
- `cursor` - Cursor IDE (`.cursor/mcp.json`)
- `opencode` - OpenCode (`opencode.json`)

### Quick Start

#### 1. List Available Operations

See what endpoints are available in an OpenAPI spec:

```bash
openapi-mcp-proxy list https://api.example.com/openapi.json
# or
openapi-mcp-proxy list ./my-api-spec.yaml
```

Output:
```
  GET    /users       listUsers
  POST   /users       createUser
  GET    /users/{id}  getUserById
  DELETE /users/{id}  deleteUser
```

#### 2. Register with Your AI Agent

**Option A: Use the built-in config manager (recommended)**

```bash
# Add specific operations for Claude Desktop
openapi-mcp-proxy mcp add --agent claude-desktop https://api.example.com/openapi.json listUsers getUserById

# Add all operations (with confirmation if >10 endpoints)
openapi-mcp-proxy mcp add --agent claude-desktop https://api.example.com/openapi.json '*'

# Interactive mode - browse and select operations
openapi-mcp-proxy mcp add --agent cursor https://api.example.com/openapi.json

# Manage existing entries
openapi-mcp-proxy mcp manage --agent opencode
```

**Option B: Manual configuration**

**Claude Desktop** (`~/Library/Application Support/Claude/claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "openapi-my-api": {
      "command": "openapi-mcp-proxy",
      "args": ["proxy", "https://api.example.com/openapi.json", "listUsers", "getUserById"]
    }
  }
}
```

**OpenCode** (`opencode.json`):
```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "openapi-my-api": {
      "type": "local",
      "command": ["openapi-mcp-proxy", "proxy", "./my-api.yaml", "searchItems", "createOrder"]
    }
  }
}
```

**Cursor** (`.cursor/mcp.json`):
```json
{
  "mcpServers": {
    "my-api": {
      "command": "openapi-mcp-proxy",
      "args": ["proxy", "https://api.example.com/openapi.json", "listUsers"]
    }
  }
}
```

### How It Works

1. **Spec Loading** - Loads OpenAPI 3.x specs from URL or file (JSON or YAML)
2. **Schema Conversion** - Converts OpenAPI schemas to MCP-compatible JSON schemas
3. **Tool Generation** - Each selected operation becomes an MCP tool with:
   - Input schema from path/query/header parameters and request body
   - Output schema from 200/201/default response
   - Description from operation summary
4. **Request Execution** - When called, constructs and executes HTTP requests using http4s

### Example: EVE Online ESI API

The repository includes an example configuration for the EVE Online ESI API. See `.mcp.json`:

```json
{
  "mcpServers": {
    "eve-online": {
      "command": "openapi-mcp-proxy",
      "args": ["proxy", "https://esi.evetech.net/latest/swagger.json", 
        "get_characters_character_id", 
        "get_corporations_corporation_id",
        "post_universe_ids"]
    }
  }
}
```

### Configuration Management

The `mcp` subcommands handle different config file formats automatically:

| Agent | Config File | Format |
|-------|-------------|--------|
| Claude | `.mcp.json` | `{ "mcpServers": {...} }` |
| Claude Desktop | `~/Library/Application Support/Claude/claude_desktop_config.json` | `{ "mcpServers": {...} }` |
| Cursor | `.cursor/mcp.json` | `{ "mcpServers": {...} }` |
| OpenCode | `opencode.json` | `{ "$schema": "...", "mcp": {...} }` |

Server names are auto-derived from the spec title (e.g., "EVE Swagger Interface" → `openapi-eve-swagger-interface`).

### Tips

- **Start small** - Add only the operations you need to avoid bloating the context window
- **Use wildcards carefully** - Adding `*` includes all operations; you'll be warned if >10 endpoints
- **Mix and match** - Can run multiple openapi-mcp-proxy instances for different APIs
- **Security** - The proxy executes HTTP requests as-is; ensure your API has proper auth if needed

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
