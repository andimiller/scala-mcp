package net.andimiller.mcp.core.protocol

import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.content.Content

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json

/** Role in a prompt message */
enum PromptRole:

  case User

  case Assistant

/** Argument definition for a prompt */
case class PromptArgument(
    name: String,
    description: Option[String] = None,
    required: Boolean = false
) derives Encoder.AsObject,
      Decoder

/** Prompt definition in MCP protocol */
case class PromptDefinition(
    name: String,
    description: Option[String] = None,
    arguments: List[PromptArgument] = Nil
) derives Encoder.AsObject,
      Decoder

/** Message within a prompt */
case class PromptMessage(
    role: PromptRole,
    content: Content
) derives Encoder.AsObject,
      Decoder

object PromptMessage:

  def user(content: String): PromptMessage =
    PromptMessage(PromptRole.User, Content.Text(content))

  def user(content: Content): PromptMessage =
    PromptMessage(PromptRole.User, content)

  def assistant(content: String): PromptMessage =
    PromptMessage(PromptRole.Assistant, Content.Text(content))

  def assistant(content: Content): PromptMessage =
    PromptMessage(PromptRole.Assistant, content)

/** Request to list available prompts */
case class ListPromptsRequest(
    cursor: Option[String] = None
) derives Encoder.AsObject,
      Decoder

/** Response listing available prompts */
case class ListPromptsResponse(
    prompts: List[PromptDefinition],
    nextCursor: Option[String] = None
) derives Encoder.AsObject,
      Decoder

/** Request to get a prompt */
case class GetPromptRequest(
    name: String,
    arguments: Map[String, Json] = Map.empty
) derives Encoder.AsObject,
      Decoder

/** Response from getting a prompt */
case class GetPromptResponse(
    description: Option[String] = None,
    messages: List[PromptMessage]
) derives Encoder.AsObject,
      Decoder
