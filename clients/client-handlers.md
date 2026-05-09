# Client handlers

The MCP spec lets servers call back to clients for capabilities the client
advertises during `initialize`:

- `sampling/createMessage` — server asks the client to run a chat completion
  (typical for an LLM-host client)
- `elicitation/create` — server asks the client to gather input from the user
- `roots/list` — server asks for the filesystem roots the client exposes

`ClientHandler[F]` is the trait that responds to those requests. It also has
a `handleNotification` hook for callback-style consumption of server-initiated
notifications (the same notifications also surface via the
`McpClient.notifications` stream — pick whichever style fits your code).

## The default: noop

If you don't pass a handler, you get `ClientHandler.noop[F]` — every
incoming server request gets a `MethodNotFound` reply. This is the correct
behaviour for a client that didn't advertise any callback capabilities,
since servers will only call methods you've opted in to.

## Building a handler

`ClientHandler.of` takes two `PartialFunction`s — one for requests, one
for notifications — and falls back to `MethodNotFound` / no-op for
anything not covered.

```scala
import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import net.andimiller.mcp.core.client.ClientHandler
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

val handler: ClientHandler[IO] =
  ClientHandler.of[IO](
    requests = {
      case "roots/list" =>
        (_: RequestId, _: Option[Json]) =>
          IO.pure(
            Right(
              Json.obj(
                "roots" -> Json.arr(
                  Json.obj("uri" -> "file:///workspace".asJson, "name" -> "workspace".asJson)
                )
              )
            )
          )
    },
    notifications = {
      case "notifications/message" =>
        params => IO.println(s"server log: ${params.fold("")(_.noSpaces)}")
    }
  )
```

Each request handler returns `F[Either[JsonRpcError, Json]]` — the framework
wraps the value into a JSON-RPC `Response` and sends it back over the
transport. If your handler raises an unhandled exception, the framework
converts it into an `internalError` response instead of crashing the
session loop.

## Advertising matching capabilities

A well-behaved server only issues server-initiated requests for
capabilities the client advertised during `initialize`. Pass a populated
`ClientCapabilities` to the builder so handlers actually get exercised:

```scala
import cats.effect.IO
import net.andimiller.mcp.core.protocol.{
  ClientCapabilities, ElicitationCapabilities, FormElicitationCapability,
  Implementation, RootsCapabilities, SamplingCapabilities
}
import net.andimiller.mcp.stdio.StdioMcpClient

val capabilities: ClientCapabilities = ClientCapabilities(
  sampling    = Some(SamplingCapabilities()),
  elicitation = Some(ElicitationCapabilities(form = Some(FormElicitationCapability()))),
  roots       = Some(RootsCapabilities(listChanged = Some(false)))
)

val resource =
  StdioMcpClient
    .builder[IO]
    .withCommand("./my-server-binary")
    .withInfo(Implementation("my-client", "0.1.0"))
    .withCapabilities(capabilities)
    .withHandler(handler)
    .connect
```

The `withCapabilities` and `withHandler` knobs work identically on
`StreamableHttpMcpClient.builder`.

## A worked example: sampling

`sampling/createMessage` is the most useful callback for an LLM-host
client — the server asks "please run this completion for me." A minimal
implementation looks like:

```scala
import io.circe.{Decoder, Encoder}

case class TextContent(`type`: String, text: String) derives Encoder.AsObject, Decoder
case class SamplingMessage(role: String, content: TextContent) derives Encoder.AsObject, Decoder
case class CreateRequest(messages: List[SamplingMessage]) derives Decoder
case class CreateResponse(role: String, content: TextContent, model: String) derives Encoder.AsObject

def runCompletion(messages: List[SamplingMessage]): IO[String] =
  IO.pure(messages.lastOption.map(_.content.text).getOrElse("(no input)"))

val samplingHandler: ClientHandler[IO] =
  ClientHandler.of[IO](
    requests = {
      case "sampling/createMessage" =>
        (_: RequestId, params: Option[Json]) =>
          val parsed = params
            .toRight(JsonRpcError.invalidParams("missing params"))
            .flatMap(_.as[CreateRequest].left.map(e => JsonRpcError.invalidParams(e.getMessage)))
          parsed match
            case Left(err)  => IO.pure(Left(err))
            case Right(req) =>
              runCompletion(req.messages).map { reply =>
                Right(
                  CreateResponse(
                    role = "assistant",
                    content = TextContent("text", reply),
                    model = "demo"
                  ).asJson.deepDropNullValues
                )
              }
    }
  )
```

For a complete, working version that wires sampling and elicitation through
to a real OpenAI-compatible LLM, see the
[example LLM harness](../examples/harness.md).

## Notifications: stream vs callback

Server-initiated notifications are delivered to **both** the
`ClientHandler.handleNotification` callback and the `McpClient.notifications`
stream — pick whichever side fits the piece of code that needs them:

- The **callback style** is convenient when reactions are local —
  incrementing a counter, updating a `Ref`, logging.
- The **stream style** is better for fan-out (multiple subscribers with
  independent backpressure) and for composing with other fs2 streams.
