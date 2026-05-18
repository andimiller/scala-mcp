package net.andimiller.mcp.examples.harness

import cats.effect.kernel.Async
import cats.syntax.all.*

import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.protocol.content.Content

import io.circe.Json
import io.circe.parser.parse

/** Bridges MCP tool definitions, plus synthetic resource-access tools, to OpenAI's tool-calling shape. Tool names are
  * namespaced as `serverName__toolName` so the LLM can pick a server when names collide; the double underscore stays
  * inside OpenAI's `^[a-zA-Z0-9_-]+$` constraint.
  */
object ToolBridge:

  private val Sep = "__"

  /** A route describes how to handle one OpenAI tool call. Real MCP tools route to `callTool`; synthetic resource tools
    * call the dedicated client methods.
    */
  enum Route[F[_]]:

    case Tool(client: McpClient[F], toolName: String)

    case ListResources(client: McpClient[F])

    case ReadResource(client: McpClient[F])

  private val emptyObjectSchema: Json = Json.obj(
    "type"       -> Json.fromString("object"),
    "properties" -> Json.obj()
  )

  private val readResourceSchema: Json = Json.obj(
    "type"       -> Json.fromString("object"),
    "properties" -> Json.obj(
      "uri" -> Json.obj(
        "type"        -> Json.fromString("string"),
        "description" -> Json.fromString("Resource URI to read")
      )
    ),
    "required" -> Json.arr(Json.fromString("uri"))
  )

  /** Aggregate tools from every connected server. For servers that advertise `resources`, also synthesise
    * `<server>__list_resources` and `<server>__read_resource` so the LLM can browse and read resources via the same
    * tool-calling channel.
    */
  def collect[F[_]: Async](
      clients: Map[String, McpClient[F]]
  ): F[(List[OpenAiTypes.ToolDef], Map[String, Route[F]])] =
    clients.toList.traverse { case (serverName, client) =>
      client.listTools().map { resp =>
        val realTools = resp.tools.map { td =>
          val ns      = s"$serverName$Sep${td.name}"
          val toolDef = OpenAiTypes.ToolDef(function =
            OpenAiTypes.FunctionDef(
              name = ns,
              description = td.description.getOrElse(""),
              parameters = td.inputSchema
            )
          )
          (toolDef, ns -> Route.Tool[F](client, td.name))
        }
        val syntheticResourceTools =
          if client.serverCapabilities.resources.isDefined then
            val listName = s"$serverName${Sep}list_resources"
            val readName = s"$serverName${Sep}read_resource"
            List(
              (
                OpenAiTypes.ToolDef(function =
                  OpenAiTypes.FunctionDef(
                    name = listName,
                    description = s"List resources exposed by the '$serverName' MCP server",
                    parameters = emptyObjectSchema
                  )
                ),
                listName -> Route.ListResources[F](client)
              ),
              (
                OpenAiTypes.ToolDef(function =
                  OpenAiTypes.FunctionDef(
                    name = readName,
                    description =
                      s"Read a resource by URI from the '$serverName' MCP server. URIs come from list_resources.",
                    parameters = readResourceSchema
                  )
                ),
                readName -> Route.ReadResource[F](client)
              )
            )
          else Nil
        realTools ++ syntheticResourceTools
      }
    }.map { perServer =>
      val flat = perServer.flatten
      (flat.map(_._1), flat.map(_._2).toMap)
    }

  /** Execute one OpenAI tool call by routing to the right MCP client. */
  def runCall[F[_]: Async](
      routes: Map[String, Route[F]]
  )(call: OpenAiTypes.ToolCall): F[OpenAiTypes.ChatMessage] =
    routes.get(call.function.name) match
      case None =>
        Async[F].pure(
          OpenAiTypes.ChatMessage.tool(call.id, s"error: no tool named '${call.function.name}'")
        )
      case Some(route) =>
        dispatch(route, call.function.arguments)
          .map(body => OpenAiTypes.ChatMessage.tool(call.id, body))
          .handleError(err => OpenAiTypes.ChatMessage.tool(call.id, s"[harness error] ${err.getMessage}"))

  private def dispatch[F[_]: Async](route: Route[F], rawArgs: String): F[String] =
    val argsJson =
      if rawArgs.trim.isEmpty then Right(Json.obj())
      else parse(rawArgs)
    Async[F]
      .fromEither(argsJson.leftMap(t => new RuntimeException(s"bad tool arguments JSON: ${t.getMessage}")))
      .flatMap { args =>
        route match
          case Route.Tool(client, toolName) =>
            client.callTool(toolName, args).map { resp =>
              val text = resp.content.collect { case Content.Text(t, _, _) => t }.mkString("\n")
              if resp.isError then s"[tool error]\n$text" else text
            }
          case Route.ListResources(client) =>
            client.listResources().map { resp =>
              if resp.resources.isEmpty then "(no resources exposed)"
              else
                resp.resources
                  .map(r => s"- ${r.uri}  ${r.name}${r.description.fold("")(d => s" — $d")}")
                  .mkString("\n")
            }
          case Route.ReadResource(client) =>
            args.hcursor.get[String]("uri") match
              case Left(err) =>
                Async[F].raiseError(new RuntimeException(s"read_resource: missing 'uri' argument: ${err.getMessage}"))
              case Right(uri) =>
                client.readResource(uri).map { resp =>
                  resp.contents
                    .map(c => c.text.getOrElse(c.blob.fold("(empty)")(b => s"(binary, ${b.length} base64 chars)")))
                    .mkString("\n")
                }
      }
