package mcp.core.protocol

import io.circe.{Encoder, Decoder}

/** Resource definition in MCP protocol */
case class ResourceDefinition(
  uri: String,
  name: String,
  description: Option[String] = None,
  mimeType: Option[String] = None
) derives Encoder.AsObject, Decoder

/** Content of a resource */
case class ResourceContent(
  uri: String,
  mimeType: Option[String] = None,
  text: Option[String] = None,
  blob: Option[String] = None  // Base64-encoded binary data
) derives Encoder.AsObject, Decoder

object ResourceContent:
  def text(uri: String, text: String, mimeType: Option[String] = None): ResourceContent =
    ResourceContent(uri, mimeType, Some(text), None)

  def blob(uri: String, blob: String, mimeType: Option[String] = None): ResourceContent =
    ResourceContent(uri, mimeType, None, Some(blob))

/** Request to list available resources */
case class ListResourcesRequest(
  cursor: Option[String] = None
) derives Encoder.AsObject, Decoder

/** Response listing available resources */
case class ListResourcesResponse(
  resources: List[ResourceDefinition],
  nextCursor: Option[String] = None
) derives Encoder.AsObject, Decoder

/** Request to read a resource */
case class ReadResourceRequest(
  uri: String
) derives Encoder.AsObject, Decoder

/** Response from reading a resource */
case class ReadResourceResponse(
  contents: List[ResourceContent]
) derives Encoder.AsObject, Decoder

/** Request to subscribe to resource updates */
case class SubscribeRequest(
  uri: String
) derives Encoder.AsObject, Decoder

/** Request to unsubscribe from resource updates */
case class UnsubscribeRequest(
  uri: String
) derives Encoder.AsObject, Decoder

/** Notification of resource list changes */
case class ResourceListChangedNotification() derives Encoder.AsObject, Decoder

/** Notification of resource content update */
case class ResourceUpdatedNotification(
  uri: String
) derives Encoder.AsObject, Decoder
