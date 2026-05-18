# tapir

`mcp-tapir` · JVM

Bridge module that turns any `sttp.tapir.Schema[A]` into a `JsonSchema[A]`.
Drop this in if you already have Tapir-derived schemas elsewhere in your
application and want to reuse them as MCP tool input/output schemas without
deriving twice.

```scala
libraryDependencies += "net.andimiller.mcp" %% "mcp-tapir" % "0.11.0"
```

Importing the bridge brings a `given JsonSchema[A]` into scope for any
type that already has a `sttp.tapir.Schema[A]`. Use it with the `tool`
builder exactly like a directly-derived schema:

```scala
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema
import net.andimiller.mcp.tapir.given
import net.andimiller.mcp.core.server.*

case class Echo(message: String) derives Schema, Decoder, Encoder.AsObject

val echoTool: Tool[IO, Unit] =
  tool.name("echo").in[Echo].out[Echo]
    .run(req => IO.pure(req))
```
