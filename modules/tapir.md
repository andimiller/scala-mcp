# tapir

`mcp-tapir` · JVM

Bridge module that turns any `sttp.tapir.Schema[A]` into a `JsonSchema[A]`.
Drop this in if you already have Tapir-derived schemas elsewhere in your
application and want to reuse them as MCP tool input/output schemas without
deriving twice.

```scala
libraryDependencies += "net.andimiller.mcp" %% "mcp-tapir" % "0.9.0"
```
