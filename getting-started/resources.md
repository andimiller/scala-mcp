# Resources & resource templates

Resources expose readable data to clients (e.g. `file:///config.json`,
`app://status`). Resource templates expose **parametrised** URIs — clients
can fill in path segments to read specific instances. Snippets below are
type-checked by mdoc.

```scala
import cats.effect.IO
import cats.syntax.all.*
import java.time.Instant
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.protocol.ResourceContent

case class Item(id: String):
  def toJson: String = s"""{"id":"$id"}"""

def lookupItem(id: String): IO[Item] = IO.pure(Item(id))

def readNote(user: String, note: String): IO[ResourceContent] =
  IO.pure(ResourceContent.text(s"app://users/$user/notes/$note", "...", Some("application/json")))
```

## Resource creation

### Static content

Use `.staticContent` when the body is a fixed string at server-construction
time:

```scala
val staticRes: McpResource[IO, Unit] =
  resource
    .uri("file:///config.json")
    .name("Config File")
    .description("Application config")
    .mimeType("application/json")
    .staticContent[IO]("""{"key": "value"}""")
```

### Dynamic content

Use `.read` when the body is computed on each read:

```scala
val dynamicRes: McpResource[IO, Unit] =
  resource
    .uri("app://status")
    .name("Server Status")
    .mimeType("text/plain")
    .read(() => IO.pure(s"Status at ${Instant.now}"))
```

### Contextual

Resolved per-session with a context value:

```scala
trait MyStatusCtx:
  def getStatus: IO[String]

val ctxRes: McpResource[IO, MyStatusCtx] =
  contextualResource[MyStatusCtx]
    .uri("app://status")
    .name("Server Status")
    .read(ctx => ctx.getStatus)
```

The factory methods `McpResource.static[IO](...)` and `McpResource.dynamic[IO](...)`
are equivalent to the fluent forms above and remain available.

## Resource template creation

Resource templates use the same fluent entry point (`resourceTemplate`) but
with a `.path` DSL that builds typed parameter extraction. Segments combine
with `*>` / `<*` and named segments are extracted:

```scala
val itemTemplate: ResourceTemplate[IO, Unit] =
  resourceTemplate
    .path(path.static("app://items/") *> path.named("id"))
    .name("Item by ID")
    .description("Look up a single item by its ID")
    .mimeType("application/json")
    .read { id =>
      lookupItem(id).map(item =>
        ResourceContent.text(s"app://items/$id", item.toJson, Some("application/json"))
      )
    }
```

Multi-parameter templates combine named segments with `.tupled`:

```scala
val noteTemplate: ResourceTemplate[IO, Unit] =
  resourceTemplate
    .path(
      path.static("app://users/") *>
        (path.named("user"), path.static("/notes/") *> path.named("note")).tupled
    )
    .name("Note by User & ID")
    .read { case (user, note) => readNote(user, note) }
```
