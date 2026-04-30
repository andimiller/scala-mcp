# golden-munit

`mcp-golden-munit` · JVM · Scala.js · Scala Native

Snapshot-testing helpers for MCP server specs, built on
[munit](https://scalameta.org/munit/) and `munit-cats-effect`. Captures your
server's tools, resources, resource templates, prompts, and capabilities as a
JSON golden file and fails the test if the spec changes unexpectedly.

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-golden-munit" % "0.9.0" % Test
```

See [Testing → Golden testing](../testing/golden-testing.md) for the full guide.
