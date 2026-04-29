# Prompts

Prompts are reusable message templates the client can render or inject into a
conversation. They can be static (fixed messages) or dynamic (computed from
arguments / per-session context). Snippets below are type-checked by mdoc.

```scala mdoc:silent
import cats.effect.IO
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.protocol.PromptMessage
```

## Static prompt

No arguments, fixed messages — use `.messages`:

```scala mdoc:silent
val staticPrompt =
  prompt
    .name("explain_protocol")
    .description("Explain the MCP protocol")
    .messages[IO](List(
      PromptMessage.user("Please explain how MCP works."),
      PromptMessage.assistant("MCP is a JSON-RPC 2.0 protocol …")
    ))
```

## Dynamic prompt

Generated from client-supplied arguments — declare each argument with
`.argument(name, description, required)`, then return the messages from
`.generate`:

```scala mdoc:silent
val dynamicPrompt =
  prompt
    .name("code_review")
    .description("Code review prompt")
    .argument("code", Some("Code to review"), required = true)
    .generate { args =>
      val code = args.get("code").flatMap(_.asString).getOrElse("")
      IO.pure(List(PromptMessage.user(s"Please review this code: $code")))
    }
```

## Contextual prompt

Generated from per-session context (the second lambda parameter receives the
client-supplied arguments map):

```scala mdoc:silent
trait MyHistoryCtx:
  def history: IO[String]

val ctxPrompt =
  contextualPrompt[MyHistoryCtx]
    .name("review_day")
    .generate((ctx, _) => ctx.history.map(h => List(PromptMessage.user(h))))
```

The factory methods `Prompt.static[IO](...)` and `Prompt.dynamic[IO](...)` are
equivalent to the fluent forms above and remain available.
