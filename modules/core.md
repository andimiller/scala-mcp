# core

`mcp-core` · JVM · Scala.js · Scala Native

The foundation. Defines the MCP protocol types (tools, resources, resource
templates, prompts, capabilities), the `Server[F]` abstraction, the fluent
builders (`tool`, `resource`, `prompt`, plus their `contextual*` variants),
and the JSON Schema derivation machinery. Every other module depends on this.

```scala
libraryDependencies += "net.andimiller.mcp" %%% "mcp-core" % "0.10.0"
```

A taste of the surface — typed I/O case classes derive JSON Schema and circe
codecs in one shot, and `tool` / `resource` / `prompt` are the fluent
builders that produce values you hand to `ServerBuilder`:

```scala
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.server.*

case class Greet(name: String)    derives JsonSchema, Decoder
case class Greeting(text: String) derives JsonSchema, Encoder.AsObject

val greetTool: Tool.Resolved[IO] =
  tool.name("greet").in[Greet].out[Greeting]
    .run(req => IO.pure(Greeting(s"Hello, ${req.name}!")))

val server: IO[Server[IO]] =
  ServerBuilder[IO]("my-server", "1.0.0").withTool(greetTool).build
```

Most user docs live in [Getting Started](../getting-started/quick-start.md) — start
with [Tools](../getting-started/tools.md), [Resources](../getting-started/resources.md),
and [Prompts](../getting-started/prompts.md) to see what the core builders look like
in practice.
