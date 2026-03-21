package mcp.core.protocol

import io.circe.{Encoder, Decoder}

/** Implementation information */
case class Implementation(
  name: String,
  version: String
) derives Encoder.AsObject, Decoder

/** Root for resource access */
case class Root(
  uri: String,
  name: Option[String] = None
) derives Encoder.AsObject, Decoder

/** Tool capabilities */
case class ToolCapabilities(
  listChanged: Option[Boolean] = None
) derives Encoder.AsObject, Decoder

/** Resource capabilities */
case class ResourceCapabilities(
  subscribe: Option[Boolean] = None,
  listChanged: Option[Boolean] = None
) derives Encoder.AsObject, Decoder

/** Prompt capabilities */
case class PromptCapabilities(
  listChanged: Option[Boolean] = None
) derives Encoder.AsObject, Decoder

/** Logging capabilities */
case class LoggingCapabilities() derives Encoder.AsObject, Decoder

/** Server capabilities */
case class ServerCapabilities(
  tools: Option[ToolCapabilities] = None,
  resources: Option[ResourceCapabilities] = None,
  prompts: Option[PromptCapabilities] = None,
  logging: Option[LoggingCapabilities] = None
) derives Encoder.AsObject, Decoder

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

/** Sampling capabilities */
case class SamplingCapabilities() derives Encoder.AsObject, Decoder

/** Client capabilities */
case class ClientCapabilities(
  sampling: Option[SamplingCapabilities] = None,
  roots: Option[RootsCapabilities] = None
) derives Encoder.AsObject, Decoder

object ClientCapabilities:
  def empty: ClientCapabilities = ClientCapabilities()

/** Roots capabilities */
case class RootsCapabilities(
  listChanged: Option[Boolean] = None
) derives Encoder.AsObject, Decoder

/** Initialize request parameters */
case class InitializeRequest(
  protocolVersion: String,
  capabilities: ClientCapabilities,
  clientInfo: Implementation
) derives Encoder.AsObject, Decoder

/** Initialize response */
case class InitializeResponse(
  protocolVersion: String,
  capabilities: ServerCapabilities,
  serverInfo: Implementation
) derives Encoder.AsObject, Decoder

/** Initialized notification (sent after initialize) */
case class InitializedNotification() derives Encoder.AsObject, Decoder

/** Ping request */
case class PingRequest() derives Encoder.AsObject, Decoder
