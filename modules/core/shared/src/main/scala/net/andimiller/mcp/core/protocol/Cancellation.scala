package net.andimiller.mcp.core.protocol

import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

/** Params for the `notifications/cancelled` JSON-RPC notification. */
case class CancelledNotificationParams(
  requestId: RequestId,
  reason: Option[String] = None
) derives Encoder.AsObject, Decoder
