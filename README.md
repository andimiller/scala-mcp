# Scala 3 MCP Server Library

A Scala 3 library for building [Model Context Protocol (MCP)](https://modelcontextprotocol.io) servers. MCP is a JSON-RPC 2.0 based protocol that allows AI applications like Claude to access tools, resources, and prompts.

## Features

- **Type-Safe Protocol Implementation** - Complete MCP protocol implementation with Scala 3 enums and opaque types
- **Automatic Schema Derivation** - Use `derives JsonSchema` to automatically generate JSON schemas for your tools
- **Multiple Transport Layers** - Support for stdio, HTTP+SSE, and custom transports
- **Fluent Builder API** - Easy-to-use builders for constructing servers, tools, resources, and prompts
- **Effect-Polymorphic** - Built on Cats Effect for composable, functional programming
- **Zero Boilerplate** - Leverage Scala 3's `derives` syntax for automatic codec derivation

## Quick Start

### Adding the Dependency

```scala
libraryDependencies ++= Seq(
  "net.andimiller.mcp" %% "mcp-core" % "0.1.0-SNAPSHOT",
  "net.andimiller.mcp" %% "mcp-stdio" % "0.1.0-SNAPSHOT"
)
```

### Creating a Simple Tool

```scala
import cats.effect.{IO, IOApp}
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.{ServerBuilder, Tool}

// Define your request/response types with automatic derivation
case class WeatherRequest(
  location: String,
  units: Option[String] = Some("celsius")
) derives JsonSchema, Decoder

case class WeatherResponse(
  temperature: Double,
  conditions: String
) derives JsonSchema, Encoder

// Create a tool with automatic schema generation
val weatherTool = Tool.buildNamed[IO, WeatherRequest, WeatherResponse](
  "get_weather",
  "Get current weather for a location"
) { request =>
  // Your implementation here
  IO.pure(WeatherResponse(22.5, "Sunny"))
}

// Build and run the server
object WeatherServer extends IOApp.Simple:
  def run: IO[Unit] =
    ServerBuilder[IO]("weather-server", "1.0.0")
      .withTool(weatherTool)
      .build
      .flatMap { server =>
        // Run over stdio transport
        import net.andimiller.mcp.stdio.StdioTransport
        StdioTransport.run(server)
      }
```

## Architecture

### Module Structure

- **core** - Protocol types, server abstraction, schema derivation, JSON codecs
- **stdio** - stdin/stdout transport for subprocess-based servers
- **http4s** - HTTP + Server-Sent Events transport (planned)
- **tapir** - Tapir endpoint definitions (planned)
- **examples** - Example servers demonstrating library features

### Key Design Decisions

1. **Tagless Final Pattern** - Effect-polymorphic using `F[_]: Async`
2. **Transport Agnostic Core** - Core defines `MessageChannel[F]` abstraction
3. **Scala 3 Native** - Leverages enums, opaque types, inline macros, and `derives`
4. **Custom JSON Schema** - Lightweight schema derivation tailored to MCP needs

## API Overview

### Tool Creation

Three ways to create tools:

```scala
// 1. Simple inline helper (automatically captures name)
val tool1 = Tool.build { (req: MyRequest) =>
  IO.pure(MyResponse(...))
}(using Name("my_tool"))

// 2. Named helper with explicit name and description
val tool2 = Tool.buildNamed[IO, MyRequest, MyResponse](
  "my_tool",
  "Tool description"
) { req => IO.pure(MyResponse(...)) }

// 3. Fluent builder for more control
val tool3 = Tool.builder[IO]
  .name("my_tool")
  .description("Tool description")
  .schemaFrom[MyRequest]
  .handler { (req: MyRequest) => IO.pure(MyResponse(...)) }
```

### Resource Creation

```scala
// Static resource
val resource1 = Resource.static[IO](
  uri = "file:///config.json",
  name = "Config File",
  content = """{"key": "value"}""",
  mimeType = Some("application/json")
)

// Dynamic resource
val resource2 = Resource.dynamic[IO](
  uri = "file:///status",
  name = "Server Status",
  reader = () => IO.pure(s"Status at ${Instant.now}")
)
```

### Prompt Creation

```scala
val prompt = Prompt.dynamic[IO](
  "code_review",
  args => IO.pure(List(
    PromptMessage.user(s"Please review this code: ${args("code")}"),
    PromptMessage.assistant("I'll review the code for you.")
  )),
  Some("Code review prompt"),
  List(PromptArgument("code", Some("Code to review"), required = true))
)
```

### Server Construction

```scala
val server = ServerBuilder[IO]("my-server", "1.0.0")
  .withTool(tool1)
  .withTools(tool2, tool3)
  .withResource(resource1)
  .withPrompt(prompt1)
  .enableToolNotifications
  .enableResourceSubscriptions
  .build
```

## JSON Schema Derivation

The library provides automatic JSON Schema derivation using Scala 3's `derives` clause:

```scala
case class SearchRequest(
  query: String,
  maxResults: Int = 10,
  filters: Option[List[String]] = None
) derives JsonSchema, Decoder, Encoder
```

This automatically generates:
- JSON Schema for validation
- Circe Decoder for parsing JSON input
- Circe Encoder for generating JSON output

## Examples

### Dice MCP Server (`modules/example-dice-mcp`)

A fully working stdio MCP server demonstrating all three server capabilities:

- **Tool**: `roll_dice` — roll dice using standard notation (e.g., `2d20 + 5`)
- **Resources**:
  - `dice://rules/standard` — static markdown reference for dice notation syntax
  - `dice://history` — dynamic resource showing recent roll results (populated by the tool)
- **Prompts**:
  - `explain_notation` — a prompt template that explains dice notation to the user

This uses `StdioMcpIOApp` for zero-boilerplate server setup:

```scala
object DiceMcpServer extends StdioMcpIOApp:
  override def serverName = "dice-mcp"
  override def serverVersion = "1.0.0"

  override def tools = List(...)
  override def resources = List(...)
  override def prompts = List(...)
```

Build and run:

```bash
sbt exampleDiceJVM/stage
```

Then configure it as an MCP server in your client (e.g., Claude Code):

```json
{
  "mcpServers": {
    "dice": {
      "command": "modules/example-dice-mcp/jvm/target/universal/stage/bin/example-dice-mcp"
    }
  }
}
```

### Weather Server (`modules/examples`)

A simpler example that demonstrates the core API directly (tool creation patterns, `ServerBuilder`, schema derivation) without a transport layer.

```bash
sbt "examples/runMain net.andimiller.mcp.examples.WeatherServer"
```

## Status

**Current Status**: Core implementation complete

Implemented:
- Core protocol types (JSON-RPC 2.0, MCP messages)
- JSON Schema derivation with `derives` syntax
- Server infrastructure (handlers, sessions, transport abstraction)
- Builder APIs (ServerBuilder, Tool, Resource, Prompt)
- Stdio transport
- Dice MCP server example (tools, resources, prompts)
- Weather server example (tool creation patterns)

Planned:
- HTTP4s transport with SSE
- Tapir endpoint definitions
- Annotation-based API with macros (`@tool`, `@resource`, `@prompt`)
- Resource subscriptions with live updates

## Building

```bash
# Compile all modules
sbt compile

# Run tests
sbt test

# Run example
sbt "examples/runMain net.andimiller.mcp.examples.WeatherServer"
```

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
