# golden-munit

`mcp-golden-munit` · JVM · Scala.js · Scala Native

Snapshot-testing helpers for MCP server specs, built on
[munit](https://scalameta.org/munit/) and `munit-cats-effect`. Captures your
server's tools, resources, resource templates, prompts, and capabilities as a
JSON golden file and fails the test if the spec changes unexpectedly.

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-golden-munit" % "0.12.0" % Test
```

A test class is a `McpGoldenSuite` that exposes the server you want to
snapshot. The first run writes
`src/test/resources/{goldenFileName}`; subsequent runs diff against it:

```scala
import cats.effect.IO
import net.andimiller.mcp.core.server.{Server, ServerBuilder}
import net.andimiller.mcp.golden.McpGoldenSuite

class MyServerGoldenSuite extends McpGoldenSuite:
  override def goldenFileName = "my-server.json"

  def server: IO[Server[IO]] =
    ServerBuilder[IO]("my-server", "1.0.0").build
```

See [Testing → Golden testing](../testing/golden-testing.md) for the full guide.
