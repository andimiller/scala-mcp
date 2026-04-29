# OpenAPI MCP Proxy

The OpenAPI MCP Proxy tool converts any OpenAPI-compliant REST API into an
MCP server that AI agents can interact with.

## Features

- **Automatic tool generation** — converts OpenAPI operations into MCP tools with proper JSON schemas
- **Multi-agent support** — works with Claude Desktop, Cursor, and OpenCode via automatic config generation
- **Interactive management** — interactive shell for browsing and selecting API operations
- **Flexible input** — load specs from URLs (`https://api.example.com/openapi.json`) or local files
- **Full HTTP support** — handles GET, POST, PUT, DELETE, PATCH with path params, query params, headers, and request bodies

## Installation

```bash
# Build the executable JAR
sbt openapiMcpProxy/assembly

# The JAR is created as ./openapi-mcp-proxy.jar
# Make it executable and add to your PATH
chmod +x openapi-mcp-proxy.jar
mv openapi-mcp-proxy.jar /usr/local/bin/openapi-mcp-proxy
```

## Commands

| Command | Description |
|---------|-------------|
| `list <spec>` | List all available operationIds with their HTTP method and path |
| `proxy <spec> <operationIds...>` | Run the MCP stdio proxy for selected operations |
| `mcp add [--agent] <spec> [operationIds...]` | Register operations in agent config |
| `mcp del [--agent] <name>` | Remove a server entry from config |
| `mcp manage [--agent]` | Interactive shell for managing config entries |

**Supported agents:**
- `claude` — Claude Code (`.mcp.json`)
- `claude-desktop` — Claude Desktop app (macOS only)
- `cursor` — Cursor IDE (`.cursor/mcp.json`)
- `opencode` — OpenCode (`opencode.json`)

## Quick start

### 1. List available operations

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

### 2. Register with your AI agent

**Option A: use the built-in config manager (recommended)**

```bash
# Add specific operations for Claude Desktop
openapi-mcp-proxy mcp add --agent claude-desktop https://api.example.com/openapi.json listUsers getUserById

# Add all operations (with confirmation if >10 endpoints)
openapi-mcp-proxy mcp add --agent claude-desktop https://api.example.com/openapi.json '*'

# Interactive mode — browse and select operations
openapi-mcp-proxy mcp add --agent cursor https://api.example.com/openapi.json

# Manage existing entries
openapi-mcp-proxy mcp manage --agent opencode
```

**Option B: manual configuration**

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

## How it works

1. **Spec loading** — loads OpenAPI 3.x specs from URL or file (JSON or YAML)
2. **Schema conversion** — converts OpenAPI schemas to MCP-compatible JSON schemas
3. **Tool generation** — each selected operation becomes an MCP tool with:
   - Input schema from path/query/header parameters and request body
   - Output schema from 200/201/default response
   - Description from operation summary
4. **Request execution** — when called, constructs and executes HTTP requests using http4s

## Example: EVE Online ESI API

The repository includes an example configuration for the EVE Online ESI API.
See `.mcp.json`:

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

## Configuration management

The `mcp` subcommands handle different config file formats automatically:

| Agent | Config file | Format |
|-------|-------------|--------|
| Claude | `.mcp.json` | `{ "mcpServers": {...} }` |
| Claude Desktop | `~/Library/Application Support/Claude/claude_desktop_config.json` | `{ "mcpServers": {...} }` |
| Cursor | `.cursor/mcp.json` | `{ "mcpServers": {...} }` |
| OpenCode | `opencode.json` | `{ "$schema": "...", "mcp": {...} }` |

Server names are auto-derived from the spec title (e.g., "EVE Swagger
Interface" → `openapi-eve-swagger-interface`).

## Tips

- **Start small** — add only the operations you need to avoid bloating the context window
- **Use wildcards carefully** — adding `*` includes all operations; you'll be warned if >10 endpoints
- **Mix and match** — can run multiple openapi-mcp-proxy instances for different APIs
- **Security** — the proxy executes HTTP requests as-is; ensure your API has proper auth if needed
