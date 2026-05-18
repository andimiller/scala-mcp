package net.andimiller.mcp.core.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject

/** Implementation information */
case class Implementation(
    name: String,
    version: String,
    title: Option[String] = None,
    description: Option[String] = None,
    icons: Option[List[Icon]] = None,
    websiteUrl: Option[String] = None
) derives Encoder.AsObject,
      Decoder

/** Root for resource access */
case class Root(
    uri: String,
    name: Option[String] = None,
    _meta: Option[JsonObject] = None
) derives Encoder.AsObject,
      Decoder

/** Tool capabilities */
case class ToolCapabilities(
    listChanged: Option[Boolean] = None
) derives Encoder.AsObject,
      Decoder

/** Resource capabilities */
case class ResourceCapabilities(
    subscribe: Option[Boolean] = None,
    listChanged: Option[Boolean] = None
) derives Encoder.AsObject,
      Decoder

/** Prompt capabilities */
case class PromptCapabilities(
    listChanged: Option[Boolean] = None
) derives Encoder.AsObject,
      Decoder

/** Logging capabilities */
case class LoggingCapabilities() derives Encoder.AsObject, Decoder

/** Completions capabilities (presence-only — clients use to decide whether to send `completion/complete`). */
case class CompletionsCapabilities() derives Encoder.AsObject, Decoder

/** Server capabilities */
case class ServerCapabilities(
    tools: Option[ToolCapabilities] = None,
    resources: Option[ResourceCapabilities] = None,
    prompts: Option[PromptCapabilities] = None,
    logging: Option[LoggingCapabilities] = None,
    completions: Option[CompletionsCapabilities] = None,
    experimental: Option[JsonObject] = None
) derives Encoder.AsObject,
      Decoder

object ServerCapabilities:

  def empty: ServerCapabilities = ServerCapabilities()

  def withTools(listChanged: Boolean = false): ServerCapabilities =
    ServerCapabilities(tools = Some(ToolCapabilities(Some(listChanged))))

  def withResources(subscribe: Boolean = false, listChanged: Boolean = false): ServerCapabilities =
    ServerCapabilities(resources = Some(ResourceCapabilities(Some(subscribe), Some(listChanged))))

  def withPrompts(listChanged: Boolean = false): ServerCapabilities =
    ServerCapabilities(prompts = Some(PromptCapabilities(Some(listChanged))))

  def withLogging: ServerCapabilities =
    ServerCapabilities(logging = Some(LoggingCapabilities()))

/** Sampling-context capability (presence-only — client supports `includeContext` on sampling requests). */
case class SamplingContextCapability() derives Encoder.AsObject, Decoder

/** Sampling-tools capability (presence-only — client supports tool-augmented sampling). */
case class SamplingToolsCapability() derives Encoder.AsObject, Decoder

/** Sampling capabilities */
case class SamplingCapabilities(
    context: Option[SamplingContextCapability] = None,
    tools: Option[SamplingToolsCapability] = None
) derives Encoder.AsObject,
      Decoder

/** Form-mode elicitation capability */
case class FormElicitationCapability() derives Encoder.AsObject, Decoder

/** URL-mode elicitation capability (presence-only — client supports out-of-band URL elicitation). */
case class UrlElicitationCapability() derives Encoder.AsObject, Decoder

/** Elicitation capabilities (client-side) */
case class ElicitationCapabilities(
    form: Option[FormElicitationCapability] = None,
    url: Option[UrlElicitationCapability] = None
) derives Encoder.AsObject,
      Decoder

/** Client capabilities */
case class ClientCapabilities(
    sampling: Option[SamplingCapabilities] = None,
    roots: Option[RootsCapabilities] = None,
    elicitation: Option[ElicitationCapabilities] = None,
    experimental: Option[JsonObject] = None
) derives Encoder.AsObject,
      Decoder

object ClientCapabilities:

  def empty: ClientCapabilities = ClientCapabilities()

/** Roots capabilities */
case class RootsCapabilities(
    listChanged: Option[Boolean] = None
) derives Encoder.AsObject,
      Decoder

/** Initialize request parameters */
case class InitializeRequest(
    protocolVersion: String,
    capabilities: ClientCapabilities,
    clientInfo: Implementation
) derives Encoder.AsObject,
      Decoder

/** Initialize response */
case class InitializeResponse(
    protocolVersion: String,
    capabilities: ServerCapabilities,
    serverInfo: Implementation,
    instructions: Option[String] = None,
    _meta: Option[JsonObject] = None
) derives Encoder.AsObject,
      Decoder

/** Initialized notification (sent after initialize) */
case class InitializedNotification() derives Encoder.AsObject, Decoder

/** Ping request */
case class PingRequest() derives Encoder.AsObject, Decoder

/** Notification that the tool list has changed. */
case class ToolListChangedNotification() derives Encoder.AsObject, Decoder

/** Notification that the prompt list has changed. */
case class PromptListChangedNotification() derives Encoder.AsObject, Decoder
