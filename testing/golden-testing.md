# Golden testing

The `mcp-golden-munit` module provides snapshot testing for MCP server specs.
It captures your server's tools, resources, resource templates, prompts, and
capabilities as a JSON golden file, then fails the test if the spec changes
unexpectedly.

## Adding the dependency

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-golden-munit" % "0.9.0" % Test
```

> **Scala.js note:** Your project must configure
> `scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))` for
> the test to work on JS.

## Writing a golden test

Extend `McpGoldenSuite`, override `goldenFileName`, and implement
`def server: IO[Server[IO]]`:

```scala
import cats.effect.IO
import net.andimiller.mcp.core.server.{Server, ServerBuilder}
import net.andimiller.mcp.golden.McpGoldenSuite

class MyServerGoldenSuite extends McpGoldenSuite:
  override def goldenFileName = "my-server.json"

  def server: IO[Server[IO]] =
    ServerBuilder[IO]("my-server", "1.0.0").build
```

## How it works

1. **First run** — the golden file doesn't exist yet, so the test creates
   `src/test/resources/{goldenFileName}` containing the server's full spec as
   JSON.
2. **Subsequent runs** — the test extracts the current spec and compares it
   against the golden file. Any difference fails the test with a diff.
3. **Regenerating** — delete the golden file and rerun the test to create a
   fresh snapshot.
4. **CI** — if the golden file is missing when the `CI` environment variable
   is set, the test fails immediately. Always run locally first to generate
   the file.
