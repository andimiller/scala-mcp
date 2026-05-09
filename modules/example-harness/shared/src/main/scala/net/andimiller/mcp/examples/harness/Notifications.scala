package net.andimiller.mcp.examples.harness

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Console
import cats.effect.syntax.all.*
import cats.syntax.all.*

import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.protocol.jsonrpc.Message

/** Server→client notifications are surfaced via [[McpClient.notifications]] as a stream. We spawn one background fiber
  * per client to drain it and print each notification dim. List-changed events (tools, resources, prompts) only get
  * printed; full re-collection is left to a future pass since the route maps live in the running REPL.
  *
  * Output is best-effort interleaved with the REPL — printing while the prompt is showing will cause some terminal
  * artifacting which the user can clear with a fresh keypress.
  */
object Notifications:

  /** Start one background fiber per client. The returned `Resource` keeps the fibers alive and cancels them on
    * shutdown.
    */
  def watchAll[F[_]: Async: Console](clients: Map[String, McpClient[F]]): Resource[F, Unit] =
    clients.toList.traverse_ { case (name, client) => watch(name, client) }

  private def watch[F[_]: Async: Console](serverName: String, client: McpClient[F]): Resource[F, Unit] =
    client.notifications
      .evalMap(notif => Console[F].println(format(serverName, notif)))
      .compile
      .drain
      .background
      .void

  private def format(serverName: String, n: Message.Notification): String =
    val params = n.params.fold("")(j => " " + j.noSpaces)
    Theme.notification(s"[notif $serverName] ${n.method}$params")
