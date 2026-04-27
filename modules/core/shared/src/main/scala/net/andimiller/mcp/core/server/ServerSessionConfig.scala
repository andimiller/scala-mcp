package net.andimiller.mcp.core.server

/**
 * Tunables for [[ServerSession]].
 *
 * @param maxConcurrent Upper bound on inbound JSON-RPC messages processed in parallel by the
 *                      session's `parEvalMapUnordered`. Must be ≥ 2 to avoid the inbound-reader
 *                      deadlock when a tool awaits a server-initiated response (e.g.
 *                      `elicitation/create`) — the response is itself an inbound message.
 *                      Also bounds the maximum size of the per-session [[CancellationRegistry]].
 */
case class ServerSessionConfig(
  maxConcurrent: Int = 16
)

object ServerSessionConfig:
  val default: ServerSessionConfig = ServerSessionConfig()
