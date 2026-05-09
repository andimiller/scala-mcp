package net.andimiller.mcp.examples.harness

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.syntax.all.*

/** Drives the harness chat loop.
  *
  * User input is interpreted in three buckets:
  *   - `:q` / `:quit` / EOF → exit
  *   - lines starting with `/` → REPL slash commands (`/help`, `/prompts`, `/prompt <ns> [k=v…]`)
  *   - everything else → user message into the agent loop (LLM + tool calls until plain text)
  *
  * The agent loop is bounded by `MaxToolHops` so a tool-happy model can't spin forever.
  *
  * Assistant replies stream token-by-token via SSE; reasoning tokens (DeepSeek/GLM/OpenRouter style) stream too, dimmed
  * and labelled `thinking:`. Tool calls only print after the stream ends — by then the JSON arguments are fully
  * assembled.
  */
object Repl:

  private val MaxToolHops = 8

  /** Tracks which "lane" the streamed output is currently in so we print labels (`assistant:`, `thinking:`) once on
    * entry and a newline on transition.
    */
  private enum Mode:

    case Idle, Reasoning, Content

  def run[F[_]: Async: Console](
      llm: OpenAiClient[F],
      tools: List[OpenAiTypes.ToolDef],
      toolRoutes: Map[String, ToolBridge.Route[F]],
      promptRoutes: Map[String, PromptBridge.Route[F]]
  ): F[Unit] =
    val seedHistory = List(
      OpenAiTypes.ChatMessage.system(
        "You are a small CLI agent. Use the provided tools when they help answer the user's question. " +
          "Tool names are namespaced as 'serverName__toolName'. Some servers expose synthetic " +
          "'list_resources' and 'read_resource' tools — use them to discover and read MCP resources."
      )
    )
    loop(llm, tools, toolRoutes, promptRoutes, seedHistory)

  private def loop[F[_]: Async: Console](
      llm: OpenAiClient[F],
      tools: List[OpenAiTypes.ToolDef],
      toolRoutes: Map[String, ToolBridge.Route[F]],
      promptRoutes: Map[String, PromptBridge.Route[F]],
      history: List[OpenAiTypes.ChatMessage]
  ): F[Unit] =
    Console[F].print(Theme.prompt) *> Console[F]
      .readLineWithCharset(java.nio.charset.StandardCharsets.UTF_8)
      .attempt
      .map(_.toOption.flatMap(Option(_)))
      .flatMap {
        case None       => Async[F].unit
        case Some(line) =>
          val trimmed = line.trim
          if trimmed.isEmpty then loop(llm, tools, toolRoutes, promptRoutes, history)
          else if trimmed === ":q" || trimmed === ":quit" then Async[F].unit
          else if trimmed.startsWith("/") then
            handleSlash(trimmed, llm, tools, toolRoutes, promptRoutes, history)
              .flatMap(next => loop(llm, tools, toolRoutes, promptRoutes, next))
          else
            val withUser = history :+ OpenAiTypes.ChatMessage.user(trimmed)
            turn(llm, tools, toolRoutes, withUser, MaxToolHops)
              .flatMap(next => loop(llm, tools, toolRoutes, promptRoutes, next))
      }

  /** Dispatch a `/…` line. Returns the (possibly updated) history. */
  private def handleSlash[F[_]: Async: Console](
      line: String,
      llm: OpenAiClient[F],
      tools: List[OpenAiTypes.ToolDef],
      toolRoutes: Map[String, ToolBridge.Route[F]],
      promptRoutes: Map[String, PromptBridge.Route[F]],
      history: List[OpenAiTypes.ChatMessage]
  ): F[List[OpenAiTypes.ChatMessage]] =
    val tokens = line.drop(1).split("\\s+").toList
    tokens match
      case Nil | "" :: _  => Console[F].println(helpText).as(history)
      case "help" :: _    => Console[F].println(helpText).as(history)
      case "prompts" :: _ =>
        Console[F].println(PromptBridge.renderCatalogue(promptRoutes)).as(history)
      case "prompt" :: name :: rest =>
        invokePrompt(llm, tools, toolRoutes, promptRoutes, history, name, rest)
      case "prompt" :: Nil =>
        Console[F].errorln(Theme.err("usage: /prompt <serverName__promptName> [key=value …]")).as(history)
      case other =>
        Console[F].errorln(Theme.err(s"unknown command: /${other.mkString(" ")}  — try /help")).as(history)

  private val helpText: String =
    """Slash commands:
      |  /help                                       show this message
      |  /prompts                                    list available MCP prompts
      |  /prompt <ns_name> [key=value …]             invoke a prompt and run a turn
      |  :q | :quit                                  exit
      |Everything else is sent as a user message to the LLM.""".stripMargin

  private def invokePrompt[F[_]: Async: Console](
      llm: OpenAiClient[F],
      tools: List[OpenAiTypes.ToolDef],
      toolRoutes: Map[String, ToolBridge.Route[F]],
      promptRoutes: Map[String, PromptBridge.Route[F]],
      history: List[OpenAiTypes.ChatMessage],
      name: String,
      argTokens: List[String]
  ): F[List[OpenAiTypes.ChatMessage]] =
    promptRoutes.get(name) match
      case None =>
        Console[F].errorln(Theme.err(s"no such prompt: '$name' — try /prompts")).as(history)
      case Some(route) =>
        val parsed = for
          args <- PromptBridge.parseArgs(argTokens)
          _    <- PromptBridge.checkRequired(route.definition, args)
        yield args
        parsed match
          case Left(msg)   => Console[F].errorln(Theme.err(s"prompt: $msg")).as(history)
          case Right(args) =>
            for
              _       <- Console[F].println(Theme.info(s"invoking prompt $name"))
              resp    <- route.client.getPrompt(route.promptName, args)
              injected = resp.messages.map(PromptBridge.toChatMessage)
              _       <- resp.description.traverse_(d => Console[F].println(Theme.info(s"prompt: $d")))
              withMsgs = history ++ injected
              next    <- turn(llm, tools, toolRoutes, withMsgs, MaxToolHops)
            yield next

  /** Inner agent loop: stream the LLM response live, run any tool calls it asks for, repeat until it returns plain
    * text.
    */
  private def turn[F[_]: Async: Console](
      llm: OpenAiClient[F],
      tools: List[OpenAiTypes.ToolDef],
      routes: Map[String, ToolBridge.Route[F]],
      history: List[OpenAiTypes.ChatMessage],
      hopsLeft: Int
  ): F[List[OpenAiTypes.ChatMessage]] =
    if hopsLeft <= 0 then Console[F].errorln(Theme.err("giving up — too many tool hops")).as(history)
    else
      streamOnce(llm, tools, history).attempt.flatMap {
        case Left(err)  => Console[F].errorln(Theme.err(s"LLM call failed: ${err.getMessage}")).as(history)
        case Right(msg) =>
          // Reasoning fields are display-only; provider docs (DeepSeek et al.) explicitly say not
          // to echo them back in subsequent requests, so strip them before appending.
          val forHistory = msg.copy(reasoning_content = None, reasoning = None)
          val updated    = history :+ forHistory
          msg.tool_calls match
            case Some(calls) if calls.nonEmpty =>
              for
                _ <- calls.traverse_ { c =>
                       Console[F].println(Theme.toolCall(c.function.name, c.function.arguments))
                     }
                results <- calls.traverse(ToolBridge.runCall[F](routes))
                next    <- turn(llm, tools, routes, updated ++ results, hopsLeft - 1)
              yield next
            case _ =>
              // Content already streamed and a trailing newline already printed by streamOnce.
              Async[F].pure(updated)
      }

  /** Run a single chat-completion stream, printing tokens live and returning the assembled assistant message from the
    * `Final` event.
    */
  private def streamOnce[F[_]: Async: Console](
      llm: OpenAiClient[F],
      tools: List[OpenAiTypes.ToolDef],
      history: List[OpenAiTypes.ChatMessage]
  ): F[OpenAiTypes.ChatMessage] =
    Ref[F].of[Mode](Mode.Idle).flatMap { modeRef =>
      llm
        .chatStream(history, tools)
        .evalTap(printEvent(modeRef))
        .compile
        .fold(Option.empty[OpenAiTypes.ChatMessage]) {
          case (_, OpenAiTypes.StreamEvent.Final(m)) => Some(m)
          case (acc, _)                              => acc
        }
        .flatMap {
          case Some(m) => Async[F].pure(m)
          case None    => Async[F].raiseError(new RuntimeException("stream produced no Final event"))
        }
    }

  /** Print one streaming event, prefixing labels and newlines on lane transitions. */
  private def printEvent[F[_]: Async: Console](
      modeRef: Ref[F, Mode]
  )(ev: OpenAiTypes.StreamEvent): F[Unit] = ev match
    case OpenAiTypes.StreamEvent.ContentToken(t) =>
      modeRef.modify {
        case Mode.Content   => (Mode.Content, "")
        case Mode.Reasoning => (Mode.Content, s"\n${Theme.assistantLabel}")
        case Mode.Idle      => (Mode.Content, Theme.assistantLabel)
      }
        .flatMap(prefix => Console[F].print(prefix + t))
    case OpenAiTypes.StreamEvent.ReasoningToken(t) =>
      modeRef.modify {
        case Mode.Reasoning => (Mode.Reasoning, "")
        case Mode.Content   => (Mode.Reasoning, s"\n${Theme.thinkingLabel}")
        case Mode.Idle      => (Mode.Reasoning, Theme.thinkingLabel)
      }
        .flatMap(prefix => Console[F].print(prefix + Theme.thinkingFragment(t)))
    case OpenAiTypes.StreamEvent.Final(_) =>
      // Trailing newline only if anything was printed this turn — keeps tool-only turns silent.
      modeRef.get.flatMap {
        case Mode.Idle => Async[F].unit
        case _         => Console[F].println("")
      }
