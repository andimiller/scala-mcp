package net.andimiller.mcp.apps.tyrian

import io.circe.Json
import io.circe.parser

/** Parses inbound JSON-RPC frames from the host into HostEvents.
  *
  * Handles notifications (method present, no id) by matching the ui-notifications strings; requests
  * (`ui/resource-teardown`, `ping`); and the response to `ui/initialize` which is synthesised as `HostEvent.Ready`.
  * Other responses become `Malformed` in v1 — request/response correlation lands in a follow-up.
  *
  * Returns `Left(reason)` for anything we can't make sense of; callers typically lift this into `HostEvent.Malformed`.
  */
object BridgeParser:

  /** Tracks which request ids we've sent so we can correlate inbound JSON-RPC responses to the original method. */
  trait PendingRequests:
    def isInitializeRequest(id: Json): Boolean
    def isToolCallRequest(id: Json): Boolean = false

  /** Identity-only `PendingRequests` for tests / contexts where the parser sees responses to known ids. */
  object PendingRequests:

    /** Accepts any id as an initialize response. Useful in tests where only one outbound request exists. */
    val anyIdIsInitialize: PendingRequests = new PendingRequests:
      def isInitializeRequest(id: Json): Boolean = true

    /** Treats a fixed set of ids as initialize responses. */
    def fromSet(ids: Set[Json]): PendingRequests = new PendingRequests:
      def isInitializeRequest(id: Json): Boolean = ids.contains(id)

    /** Two id sets — one for initialize, one for tools/call. */
    def of(initializeIds: Set[Json], toolCallIds: Set[Json]): PendingRequests = new PendingRequests:
      def isInitializeRequest(id: Json): Boolean       = initializeIds.contains(id)
      override def isToolCallRequest(id: Json): Boolean = toolCallIds.contains(id)

  def parse(raw: String, pending: PendingRequests): HostEvent =
    parser.parse(raw) match
      case Left(err)   => HostEvent.Malformed(raw, s"JSON parse failed: ${err.message}")
      case Right(json) => fromJson(json, pending).fold(HostEvent.Malformed(raw, _), identity)

  def fromJson(json: Json, pending: PendingRequests): Either[String, HostEvent] =
    val cursor = json.hcursor
    val method = cursor.downField("method").as[String].toOption
    val idOpt  = cursor.downField("id").focus.filterNot(_.isNull)
    val params = cursor.downField("params").focus.getOrElse(Json.obj())

    method match
      case Some(m) =>
        idOpt match
          case None     => parseNotification(m, params)
          case Some(id) => parseRequest(m, id, params)
      case None    =>
        idOpt match
          case Some(id) => parseResponse(id, cursor.downField("result").focus, cursor.downField("error").focus, pending)
          case None     => Left("Frame has neither method nor id")

  private def parseNotification(method: String, params: Json): Either[String, HostEvent] =
    method match
      case BridgeMethods.UiNotificationsToolInputPartial   => Right(HostEvent.ToolInputPartial(params))
      case BridgeMethods.UiNotificationsToolInput          => Right(HostEvent.ToolInput(params))
      case BridgeMethods.UiNotificationsToolResult         => Right(HostEvent.ToolResult(params))
      case BridgeMethods.UiNotificationsToolCancelled      => Right(HostEvent.ToolCancelled(params))
      case BridgeMethods.UiNotificationsHostContextChanged => Right(HostEvent.HostContextChanged(params))
      case BridgeMethods.UiNotificationsSizeChanged        => Right(HostEvent.SizeChanged(params))
      case other                                           => Left(s"Unknown host notification: $other")

  private def parseRequest(method: String, id: Json, params: Json): Either[String, HostEvent] =
    val _ = params
    method match
      case BridgeMethods.UiResourceTeardown => Right(HostEvent.ResourceTeardown(id))
      case BridgeMethods.Ping               => Right(HostEvent.Ping(id))
      case other                            => Left(s"Unknown host request: $other")

  private def parseResponse(
      id: Json,
      result: Option[Json],
      error: Option[Json],
      pending: PendingRequests
  ): Either[String, HostEvent] =
    if pending.isInitializeRequest(id) then
      (result, error) match
        case (Some(r), _)    =>
          val hostContext = r.hcursor.downField("hostContext").focus.getOrElse(Json.obj())
          Right(HostEvent.Ready(hostContext))
        case (None, Some(e)) => Left(s"ui/initialize failed: ${e.noSpaces}")
        case (None, None)    => Left("Response has neither result nor error")
    else if pending.isToolCallRequest(id) then
      // Surface tools/call responses as ToolResult — same `params` shape the host-pushed `ui/notifications/tool-result`
      // uses, so a single user handler covers both code paths (LLM-invoked tools and iframe-invoked tools).
      (result, error) match
        case (Some(r), _)    => Right(HostEvent.ToolResult(r))
        case (None, Some(e)) => Left(s"tools/call failed: ${e.noSpaces}")
        case (None, None)    => Left("Response has neither result nor error")
    else Left(s"Unmatched response id: ${id.noSpaces}")
