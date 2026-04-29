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

## MCP Feature Support

Targeting MCP spec **2025-11-25**. Client-support column reflects the state of major clients (Claude Code, Claude Desktop, Cursor, opencode) per [modelcontextprotocol.io/clients](https://modelcontextprotocol.io/clients) as of April 2026:
**Universal** = all four · **Most** = three of four · **Some** = one or two · **Rare** = niche/inconsistent · **None** = no major client.

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
| `_meta` on requests/responses   | ❌        | Universal      | Spec-mandated passthrough                          |
| Sampling                        | ❌        | None           | Capability stub only                               |
| Roots                           | ❌        | Most           | Type stubs only. opencode lacks it                 |
| Completion                      | ❌        | None           | No client surfaces it                              |
| Tasks (experimental)            | ❌        | None           |                                                    |
| Tool/prompt `list_changed`      | ❌        | Universal      | Required for `listChanged` capability              |
| 2025-11-25 metadata fields      | ❌        | Some           | `title` / `icons` increasingly rendered; rest unused |

## Module Structure

| Module | Platforms | Description |
|--------|-----------|-------------|
| **core** | JVM, JS, Native | Protocol types, server abstraction, schema derivation, JSON codecs |
| **stdio** | JVM, JS, Native | stdin/stdout transport for subprocess-based servers |
| **http4s** | JVM, JS | Streamable HTTP + SSE transport via http4s Ember |
| **openapi** | JVM, JS, Native | OpenAPI 3.x schema model + tool-builder helpers |
| **redis** | JVM | Redis-backed `SessionStore` / `SessionRefs` / `StateRef` / notification sink |
| **tapir** | JVM | Bridge that turns any `sttp.tapir.Schema[A]` into a `JsonSchema[A]` |
| **golden-munit** | JVM, JS, Native | Golden testing framework for MCP server specs (munit) |
| **explorer** | JS | Browser-based UI for exploring and testing MCP servers |
| **openapi-mcp-proxy** | JVM | Standalone CLI that exposes any OpenAPI API as an MCP server |

## Quick Start

### Adding the Dependency

```scala
// For stdio-based servers
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core"  % "0.9.0",
  "net.andimiller.mcp" %%% "mcp-stdio" % "0.9.0"
)

// For HTTP-based servers
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %%% "mcp-core"   % "0.9.0",
  "net.andimiller.mcp" %%% "mcp-http4s" % "0.9.0"
)

// Optional: Redis-backed session/state for stateful HTTP servers
libraryDependencies += "net.andimiller.mcp" %% "mcp-redis" % "0.9.0"
```

### Creating a Simple Tool

```scala
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*

// Define your request/response types with automatic derivation
case class GreetRequest(name: String) derives JsonSchema, Decoder
case class GreetResponse(message: String) derives JsonSchema, Encoder.AsObject

val greetTool =
  tool.name("greet")
    .description("Greet someone by name")
    .in[GreetRequest]
    .out[GreetResponse]
    .run(req => IO.pure(GreetResponse(s"Hello, ${req.name}!")))
```

The package object `net.andimiller.mcp.core.server` exposes `tool`, `resource`,
`resourceTemplate`, `prompt`, and their `contextual*` variants as fluent
builder entry points. They are equivalent to `Tool.builder[IO]`,
`McpResource.builder`, etc., but read more naturally inside servers.

## Examples

The project ships six example MCP servers, each demonstrating a different
transport, platform, or server feature:

| Example | Transport | Platform | Port | Highlights |
|---------|-----------|----------|------|------------|
| **Dice** | Stdio | JVM / JS / Native | — | Tools, resources, prompts; form elicitation |
| **Pomodoro** | HTTP + SSE | JVM | 25000 | Per-session state, resource subscriptions, cancellation |
| **DNS** | HTTP + SSE | Scala.js (Node.js) | 8053 | Wrapping Node callback APIs into `IO` |
| **Chat** | HTTP + SSE | JVM | 27000 | Redis-backed session state and notifications |
| **Shared Notebook** | HTTP + SSE | JVM | 26000 | HTTP Basic auth; per-user contextual tools |
| **RPG Character Creator** | HTTP + SSE | JVM | 1974 | Multi-step elicitation wizard |

---

### Dice MCP Server (`modules/example-dice-mcp`)

A cross-platform stdio MCP server demonstrating tools, resources, prompts,
and form elicitation.

- **Tools**: `roll_dice` — roll dice using standard notation (e.g., `2d20 + 5`); `roll_interactive` — build a dice expression by repeatedly asking the client for `(face, count)` choices via elicitation
- **Resources**: `dice://rules/standard` (static reference), `dice://history` (recent rolls)
- **Prompt**: `explain_notation` — explains dice notation to the user

Uses `IOApp.Simple`, `ServerBuilder`, and `StdioTransport.run` with a
`SessionContext`-aware factory so each session gets its own `Random`, history
ref, and `ElicitationClient`.

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

> **Note:** the native binary must be re-linked any time the server changes —
> `sbt exampleDiceJVM/run` is the fastest feedback loop during development.

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

A JVM HTTP server demonstrating dynamic resources with subscription
notifications, server-initiated logging, argument-based prompts, and
**MCP cancellation**. Uses `McpHttp.streaming` with per-session state —
each client session gets its own `PomodoroTimer` wired to the notification sink.

- **Tools**: `start_timer`, `pause_timer`, `resume_timer`, `stop_timer`, `get_status`, `sleep_blocking` (cancellation demo — its `onCancel` hook reports how long it actually slept)
- **Resources**: `pomodoro://status` (subscribable), `pomodoro://history`, `pomodoro://timers/{name}` (template via `path.static(...) *> path.named(...)`)
- **Prompts**: `plan_session` (with `task` / `session_count` arguments), `review_day`
- **Explorer**: Bundled at `/explorer` with redirect from `/`

**Build and run (in-memory state):**

```bash
sbt examplePomodoro/run
# Server starts on http://0.0.0.0:25000
# Explorer UI at http://localhost:25000 (redirects to /explorer/index.html)
```

**Build and run (Redis-backed state, port 25001):**

```bash
# Requires a Redis server on redis://localhost:6379
sbt 'examplePomodoro/runMain net.andimiller.mcp.examples.pomodoro.PomodoroMcpServerRedis'
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

### Chat MCP Server (`modules/example-chat-mcp`)

A JVM HTTP server demonstrating **Redis-backed session state**. Each connected
session has its own username and current room, and the chat history itself is
shared across sessions through Redis. Resource subscription updates are pushed
when new messages arrive.

- **Tools**: `set_username`, `create_room`, `join_room`, `send_message`, `read_messages`
- **Resources**: `chat://rooms` (list of rooms), `chat://rooms/{room}/messages` (resource template, subscribable)
- **Prompt**: `summarize_chat` — summarises recent messages in the current room
- Built with `McpRedis.configure(...)` wrapping `McpHttp.streaming` so the per-session refs live in Redis instead of in-memory

**Build and run:**

```bash
# Requires a Redis server on redis://localhost:6379
sbt exampleChat/run
# Server starts on http://0.0.0.0:27000
```

---

### Shared Notebook MCP Server (`modules/example-shared-notebook-mcp`)

A JVM HTTP server demonstrating **HTTP authentication with per-user contextual
tools**. Authenticated users (alice/bob/charlie via Basic Auth) can write,
read, and share notes; the server uses `.authenticated[UserContext](...)` to
extract the current user and pass it as the context to every tool, resource
template, and prompt.

- **Tools**: `write_note`, `read_note`, `share_note`, `unshare_note`, `list_my_notes`, `list_shared_notes`
- **Resource templates**: `notebook://{username}` and `notebook://{username}/{note_id}` — built with the multi-segment `path` DSL
- **Prompts**: `summarize_notes`, `collaborate_with` (with arguments)

**Build and run:**

```bash
sbt exampleNotebook/run
# Server starts on http://0.0.0.0:26000
# Use HTTP Basic auth with alice/password123, bob/password456, or charlie/password789
```

---

### RPG Character Creator (`modules/example-rpg-character-creator`)

A JVM HTTP server demonstrating **multi-step elicitation over HTTP**. The
`create_character` tool walks the client through race → class → starting weapon
→ name using `requestForm` calls, where the weapon enum is generated
dynamically from the chosen class.

- **Tools**: `create_character` (interactive wizard), `list_characters`
- **Per-session state**: each session keeps its own list of created characters
- A good example of folding `ElicitResult.{Accept, Decline, Cancel}` and `ElicitationError` into a clean `EitherT` flow

**Build and run:**

```bash
sbt exampleRpgCharacterCreator/run
# Server starts on http://0.0.0.0:1974
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

Any HTTP server built with `McpHttp.basic` or `McpHttp.streaming` can serve the Explorer:

```scala
McpHttp.streaming[IO]
  .withExplorer(redirectToRoot = true) // serves at /explorer, optionally redirects / there
```

The Explorer is served at `/explorer/index.html` and defaults the connection URL to the current origin + `/mcp`.

### Building the Explorer

The Explorer assets are pre-built into the http4s module's resources. To rebuild after changes:

```bash
sbt buildExplorer
```

This compiles the Scala.js app and runs Parcel to bundle the JS and CSS into `modules/explorer/dist/`, which is then copied into the http4s classpath resources.

---

## Golden Testing

The `mcp-golden-munit` module provides snapshot testing for MCP server specs. It captures your server's tools, resources, resource templates, prompts, and capabilities as a JSON golden file, then fails the test if the spec changes unexpectedly.

### Adding the Dependency

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-golden-munit" % "0.9.0" % Test
```

> **Scala.js note:** Your project must configure `scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))` for the test to work on JS.

### Writing a Golden Test

Extend `McpGoldenSuite`, override `goldenFileName`, and implement `def server: IO[Server[IO]]`:

```scala
import cats.effect.IO
import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.golden.McpGoldenSuite

class MyServerGoldenSuite extends McpGoldenSuite:
  override def goldenFileName = "my-server.json"

  def server: IO[Server[IO]] =
    MyServer.buildServer() // return your Server[IO]
```

### How It Works

1. **First run** — the golden file doesn't exist yet, so the test creates `src/test/resources/{goldenFileName}` containing the server's full spec as JSON.
2. **Subsequent runs** — the test extracts the current spec and compares it against the golden file. Any difference fails the test with a diff.
3. **Regenerating** — delete the golden file and rerun the test to create a fresh snapshot.
4. **CI** — if the golden file is missing when the `CI` environment variable is set, the test fails immediately. Always run locally first to generate the file.

---

## API Overview

### Tool Creation

```scala
import net.andimiller.mcp.core.server.*

// Fluent builder (returns Tool[F, Unit, A, R]; .resolve gives Tool.Resolved[F])
val myTool =
  tool.name("my_tool")
    .description("Tool description")
    .in[MyRequest]
    .out[MyResponse]
    .run(req => IO.pure(MyResponse(...)))

// `.runResult` lets you return ToolResult[Out] (Success / Text / Error) directly
val mayFail =
  tool.name("risky")
    .in[MyRequest]
    .out[MyResponse]
    .runResult(req => IO.pure(ToolResult.Error("nope")))

// Contextual tool — receives a per-session context value when called
val ctxTool =
  contextualTool[MyCtx]
    .name("my_tool")
    .description("Tool description")
    .in[MyRequest]
    .out[MyResponse]
    .run((ctx, req) => ctx.doSomething(req))
```

`Tool.builder[IO]` and `Tool.contextual[MyCtx]` are equivalent to the helpers
above and remain available.

### Resource Creation

```scala
// Static resource (factory method)
McpResource.static[IO](
  resourceUri          = "file:///config.json",
  resourceName         = "Config File",
  content              = """{"key": "value"}""",
  resourceDescription  = Some("Application config"),
  resourceMimeType     = Some("application/json")
)

// Dynamic resource (factory method)
McpResource.dynamic[IO](
  resourceUri          = "app://status",
  resourceName         = "Server Status",
  reader               = () => IO.pure(s"Status at ${Instant.now}"),
  resourceMimeType     = Some("text/plain")
)

// Fluent builder
resource
  .uri("app://status")
  .name("Server Status")
  .mimeType("text/plain")
  .read(() => IO.pure(s"Status at ${Instant.now}"))

// Contextual resource (resolved per-session with a context value)
contextualResource[MyCtx]
  .uri("app://status")
  .name("Server Status")
  .read(ctx => ctx.getStatus)
```

### Resource Template Creation

The recommended style uses the `path` DSL — segments combine with `*>` / `<*`
and named segments are extracted as typed parameters:

```scala
import net.andimiller.mcp.core.server.*

resourceTemplate
  .path(path.static("app://items/") *> path.named("id"))
  .name("Item by ID")
  .description("Look up a single item by its ID")
  .mimeType("application/json")
  .read { id =>
    lookupItem(id).map(item =>
      ResourceContent.text(s"app://items/$id", item.toJson, Some("application/json"))
    )
  }

// Multi-parameter templates: combine named segments with `.tupled`
resourceTemplate
  .path(
    path.static("app://users/") *>
      (path.named("user"), path.static("/notes/") *> path.named("note")).tupled
  )
  .name("Note by User & ID")
  .read { case (user, note) => readNote(user, note) }
```

The older string-based API still works:

```scala
resourceTemplate
  .uriTemplate("app://items/{id}")
  .name("Item by ID")
  .mimeType("application/json")
  .read { uri =>
    Option.when(uri.startsWith("app://items/")) {
      val id = uri.stripPrefix("app://items/")
      lookupItem(id).map(item => ResourceContent.text(uri, item.toJson, Some("application/json")))
    }
  }
```

### Prompt Creation

```scala
// Static prompt (no arguments, fixed messages)
Prompt.static[IO](
  promptName        = "explain_protocol",
  promptDescription = Some("Explain the MCP protocol"),
  messages          = List(
    PromptMessage.user("Please explain how MCP works."),
    PromptMessage.assistant("MCP is a JSON-RPC 2.0 protocol …")
  )
)

// Dynamic prompt (factory method)
Prompt.dynamic[IO](
  promptName        = "code_review",
  promptDescription = Some("Code review prompt"),
  promptArguments   = List(
    PromptArgument("code", Some("Code to review"), required = true)
  ),
  generator = { args =>
    val code = args.get("code").flatMap(_.asString).getOrElse("")
    IO.pure(List(PromptMessage.user(s"Please review this code: $code")))
  }
)

// Fluent builder
prompt
  .name("code_review")
  .description("Code review prompt")
  .argument("code", Some("Code to review"), required = true)
  .generate { args =>
    val code = args.get("code").flatMap(_.asString).getOrElse("")
    IO.pure(List(PromptMessage.user(s"Please review this code: $code")))
  }

// Contextual prompt (per-session context)
contextualPrompt[MyCtx]
  .name("review_day")
  .generate((ctx, _) => ctx.history.map(h => List(PromptMessage.user(h))))
```

### Server Construction

#### Stdio Server

```scala
import cats.effect.{IO, IOApp}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.stdio.StdioTransport

object MyServer extends IOApp.Simple:
  def server: IO[Server[IO]] =
    ServerBuilder[IO]("my-server", "1.0.0")
      .withTool(
        tool.name("greet").in[GreetRequest].out[GreetResponse]
          .run(req => IO.pure(GreetResponse(s"Hello, ${req.name}!")))
      )
      .withResource(McpResource.static[IO](...))
      .withPrompt(Prompt.static[IO](...))
      .build

  def run: IO[Unit] = server.flatMap(StdioTransport.run[IO])
```

`StdioTransport.run` also has a factory overload — `run(ctx => F[Server[F]])` —
that hands you a `SessionContext` so you can wire per-session refs, an
`ElicitationClient`, or a notification sink in.

#### HTTP Server (basic)

```scala
import com.comcast.ip4s.*

McpHttp.basic[IO]
  .name("my-server").version("1.0.0")
  .port(port"8080")
  .withTool(...)
  .withResource(...)
  .withPrompt(...)
  .withExplorer(redirectToRoot = true)
  .serve     // : Resource[IO, http4s.server.Server]
  .useForever
```

#### HTTP Server (streaming with per-session state)

```scala
import com.comcast.ip4s.*

McpHttp.streaming[IO]
  .name("my-server").version("1.0.0")
  .port(port"25000")
  .stateful[MyTimer](ctx => MyTimer.create(ctx.sink))
  .withContextualTool(
    contextualTool[MyTimer].name("start").in[Req].out[Resp]
      .run((timer, req) => timer.start(req).map(Resp(_)))
  )
  .withContextualResource(
    contextualResource[MyTimer].uri("app://status").read(_.status)
  )
  .withContextualPrompt(
    contextualPrompt[MyTimer].name("review").generate((timer, _) => timer.summary)
  )
  .withExplorer(redirectToRoot = true)
  .enableResourceSubscriptions
  .enableLogging
  .serve.useForever
```

`.stateful[S](ctx => F[S])` chains, so multiple `stateful` / `authenticated`
calls compose into a tuple-shaped context. For example:

```scala
McpHttp.streaming[IO]
  .authenticated[User](authFn, onUnauthorized) // Ctx becomes User
  .stateful[MyState](ctx => MyState.create(ctx.refs))
  // contextual handlers now receive an (Append[MyState, User]) value
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

# Run tests with coverage (sbt-scoverage)
sbt 'coverage; coreJVM/clean; coreJVM/test; coreJVM/coverageReport'
# HTML report: modules/core/jvm/target/scala-3.3.4/scoverage-report/index.html
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

This library and associated code are available under the MIT license, see LICENSE file.

## Contributing

Feel free to make Pull Requests on github, remember to clearly state the purpose of your change and run all tests.
