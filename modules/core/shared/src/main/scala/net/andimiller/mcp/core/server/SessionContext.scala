package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import net.andimiller.mcp.core.state.SessionRefs

/** Per-session context handed to user-supplied state creators.
  *
  * Bundles every primitive a tool/resource/prompt handler might want from its session:
  *   - [[id]] — stable session identifier (a UUID for HTTP sessions, `"stdio"` for stdio).
  *   - [[channel]] — bidirectional client channel (notifications + server-initiated requests).
  *   - [[refs]] — per-session named refs (default in-memory; user-supplied factories can swap the implementation, e.g.
  *     to Redis).
  *   - [[elicitation]] — pre-built [[ElicitationClient]] backed by the channel's requester.
  *
  * Defined as a trait (not a case class) so future per-session capabilities (e.g. negotiated `ClientCapabilities`, the
  * authenticated user identity) can be added without breaking existing call sites.
  */
trait SessionContext[F[_]]:

  def id: String

  def channel: ClientChannel[F]

  def refs: SessionRefs[F]

  /** Pre-built elicitation client wired to this session's [[ServerRequester]]. */
  def elicitation: ElicitationClient[F]

  /** Convenience: the notification sink derived from the underlying channel. */
  final def sink: NotificationSink[F] = channel.sink

  /** Convenience: the server requester derived from the underlying channel. */
  final def requester: ServerRequester[F] = channel.requester

object SessionContext:

  /** Build a `SessionContext` from its parts. */
  def apply[F[_]: Async](
      sessionId: String,
      clientChannel: ClientChannel[F],
      sessionRefs: SessionRefs[F]
  ): SessionContext[F] =
    new SessionContext[F]:
      val id          = sessionId
      val channel     = clientChannel
      val refs        = sessionRefs
      val elicitation = ElicitationClient.fromRequester(clientChannel.requester)
