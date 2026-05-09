package net.andimiller.mcp.examples.harness

import cats.effect.kernel.Async
import cats.syntax.all.*

import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.protocol.PromptDefinition
import net.andimiller.mcp.core.protocol.PromptMessage
import net.andimiller.mcp.core.protocol.PromptRole
import net.andimiller.mcp.core.protocol.content.Content

import io.circe.Json

/** Surfaces MCP prompts as REPL slash commands. Prompts are user-driven templates so they don't become LLM tools;
  * instead `/prompts` lists them and `/prompt <namespaced> [k=v…]` invokes one, dropping the resulting messages into
  * the chat history.
  */
object PromptBridge:

  private val Sep = "__"

  /** Routing entry for a single prompt: which client to call, the unprefixed name, and the argument metadata so the
    * REPL can validate user input.
    */
  final case class Route[F[_]](
      client: McpClient[F],
      promptName: String,
      definition: PromptDefinition,
      serverName: String
  )

  /** Aggregate prompts from every server that advertises the capability. Servers without prompt support contribute
    * nothing.
    */
  def collect[F[_]: Async](clients: Map[String, McpClient[F]]): F[Map[String, Route[F]]] =
    clients.toList.filter { case (_, client) => client.serverCapabilities.prompts.isDefined }.traverse {
      case (serverName, client) =>
        client.listPrompts().map { resp =>
          resp.prompts.map { p =>
            val ns = s"$serverName$Sep${p.name}"
            ns -> Route(client, p.name, p, serverName)
          }
        }
    }
      .map(_.flatten.toMap)

  /** Format the prompt catalogue for `/prompts`. */
  def renderCatalogue[F[_]](routes: Map[String, Route[F]]): String =
    if routes.isEmpty then "(no prompts available)"
    else
      routes.toList
        .sortBy(_._1)
        .map { case (ns, r) =>
          val args =
            if r.definition.arguments.isEmpty then ""
            else
              " — args: " + r.definition.arguments
                .map(a => if a.required then s"${a.name}*" else a.name)
                .mkString(", ")
          val desc = r.definition.description.fold("")(d => s"\n    $d")
          s"  $ns$args$desc"
        }
        .mkString("\n")

  /** Convert an MCP `PromptMessage` to an OpenAI `ChatMessage`. Non-text content types are rendered as a textual
    * placeholder — this is a basic harness, not a multimodal one.
    */
  def toChatMessage(pm: PromptMessage): OpenAiTypes.ChatMessage =
    val role = pm.role match
      case PromptRole.User      => "user"
      case PromptRole.Assistant => "assistant"
    val text = pm.content match
      case Content.Text(t)                => t
      case Content.Image(_, mimeType)     => s"[image content: $mimeType — not rendered in harness]"
      case Content.Audio(_, mimeType)     => s"[audio content: $mimeType — not rendered in harness]"
      case Content.Resource(uri, mt, txt) =>
        txt.getOrElse(s"[resource: $uri${mt.fold("")(m => s" ($m)")}]")
    OpenAiTypes.ChatMessage(role = role, content = Some(text))

  /** Parse `key=value key2=value2` into a JSON-typed argument map for `getPrompt`. Values are passed as strings —
    * providers that need typed args can still use them.
    */
  def parseArgs(rest: List[String]): Either[String, Map[String, Json]] =
    rest.foldLeft[Either[String, Map[String, Json]]](Right(Map.empty)) { (acc, tok) =>
      acc.flatMap { m =>
        tok.split("=", 2) match
          case Array(k, v) if k.nonEmpty => Right(m + (k -> Json.fromString(v)))
          case _                         => Left(s"prompt argument '$tok' is not in key=value form")
      }
    }

  /** Validate that all required arguments are present. */
  def checkRequired(defn: PromptDefinition, args: Map[String, Json]): Either[String, Unit] =
    val missing = defn.arguments.filter(a => a.required && !args.contains(a.name)).map(_.name)
    if missing.isEmpty then Right(()) else Left(s"missing required arg(s): ${missing.mkString(", ")}")
