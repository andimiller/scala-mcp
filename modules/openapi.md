# openapi

`mcp-openapi` · JVM · Scala.js · Scala Native

OpenAPI 3.x schema model plus tool-builder helpers for converting OpenAPI
operations into MCP tools. Used by the [OpenAPI MCP Proxy](../tools/openapi-mcp-proxy.md)
CLI but also available directly when you want to embed OpenAPI-driven tool
generation inside your own server.

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-openapi" % "0.12.0"
```

Given a parsed `sttp.apispec.openapi.OpenAPI`, `OpenApiOperation` enumerates
the operations and produces an MCP `ToolDefinition` per operation —
input-schema lifted from the operation's path/query/header parameters and
request body, named after the OpenAPI `operationId`:

```scala
import sttp.apispec.openapi.OpenAPI
import net.andimiller.mcp.openapi.OpenApiOperation

def toolDefs(spec: OpenAPI): List[net.andimiller.mcp.core.protocol.ToolDefinition] =
  val ids = OpenApiOperation.listOperationIds(spec).map(_._1)
  OpenApiOperation.build(spec, ids).map(_.definition)
```

For a runnable wrapper that goes from `openapi.json` URL to a working stdio
MCP server (with auth header injection, agent-config writers, and
operation filtering), see the
[OpenAPI MCP Proxy](../tools/openapi-mcp-proxy.md) CLI.
