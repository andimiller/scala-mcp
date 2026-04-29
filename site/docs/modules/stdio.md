# stdio

`mcp-stdio` · JVM · Scala.js · Scala Native

stdin/stdout JSON-RPC transport for subprocess-based MCP servers — the format
expected by Claude Desktop and Claude Code's local server config. A server
built with `core` becomes a stdio server by handing it to
`StdioTransport.run`.

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-stdio" % "@VERSION@"
```

See [Server construction → Stdio server](../getting-started/server-construction.md#stdio-server)
for usage.
