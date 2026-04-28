package net.andimiller.mcp.core.protocol

import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import io.circe.Decoder
import io.circe.Encoder

/** Params for the `notifications/cancelled` JSON-RPC notification. */
case class CancelledNotificationParams(
    requestId: RequestId,
    reason: Option[String] = None
) derives Encoder.AsObject,
      Decoder
