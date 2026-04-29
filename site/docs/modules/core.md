# core

`mcp-core` · JVM · Scala.js · Scala Native

The foundation. Defines the MCP protocol types (tools, resources, resource
templates, prompts, capabilities), the `Server[F]` abstraction, the fluent
builders (`tool`, `resource`, `prompt`, plus their `contextual*` variants),
and the JSON Schema derivation machinery. Every other module depends on this.

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-core" % "@VERSION@"
```

Most user docs live in [Getting Started](../getting-started/quick-start.md) — start
with [Tools](../getting-started/tools.md), [Resources](../getting-started/resources.md),
and [Prompts](../getting-started/prompts.md) to see what the core builders look like
in practice.
