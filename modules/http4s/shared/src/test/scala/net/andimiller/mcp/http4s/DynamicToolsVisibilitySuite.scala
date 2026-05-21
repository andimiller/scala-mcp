package net.andimiller.mcp.http4s

import scala.concurrent.duration.*

import cats.effect.IO

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.state.SessionRefs

import fs2.concurrent.SignallingRef
import io.circe.Json
import munit.CatsEffectSuite

class DynamicToolsVisibilitySuite extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 10.seconds

  /** Trivial pass-through tool that delegates name + visibility to whatever the test wants to declare. */
  private def passthrough[Ctx](
      n: String,
      vis: Option[Ctx => IO[Boolean]] = None
  ): Tool[IO, Ctx] =
    new Tool[IO, Ctx]:
      val name                                                         = n
      val description                                                  = ""
      val inputSchema                                                  = Json.obj()
      val outputSchema                                                 = None
      override val visible                                             = vis
      def handle(call: ToolCallContext[IO, Ctx]): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text(n)), None, false))

  /** Collect every notification method published to a channel for a bounded window, then return them. Stops collecting
    * after `window` of quiet (no new messages) or when `expected` is reached.
    */
  private def collectNotifications(ch: ClientChannel[IO], expected: Int, window: FiniteDuration): IO[List[String]] =
    ch.subscribe
      .collect { case n: Message.Notification => n.method }
      .take(expected.toLong)
      .interruptAfter(window)
      .compile
      .toList

  private def session: IO[(SessionContext[IO], ClientChannel[IO])] =
    ClientChannel.create[IO].allocated.map { case (cc, _) => (SessionContext[IO]("test", cc, SessionRefs.inMemory[IO]), cc) }

  test(".state flips: invisible → visible publishes exactly one tools/list_changed") {
    val builder = McpHttp
      .streaming[IO]
      .name("t")
      .version("0")
      .state[Int](_ => IO.pure(0))
      .withContextualTool(
        new Tool[IO, SignallingRef[IO, Int]]:
          val name                                                                          = "flip"
          val description                                                                   = ""
          val inputSchema                                                                   = Json.obj()
          val outputSchema                                                                  = None
          def handle(call: ToolCallContext[IO, SignallingRef[IO, Int]]): IO[CallToolResponse] =
            call.ctx.update(_ + 1).as(CallToolResponse(List(Content.Text("flipped")), None, false))
      )
      .withContextualTool(
        passthrough[SignallingRef[IO, Int]]("conditional", Some(_.get.map(_ > 0)))
      )

    for
      sc                <- session
      (ctx, cc)          = sc
      factoryOut        <- builder.newSessionFactory("sid")(ctx)
      (server, cleanup)  = factoryOut
      fiber             <- collectNotifications(cc, expected = 2, window = 1.second).start
      _                 <- IO.sleep(50.millis)
      // 0 → 1: invisible "conditional" becomes visible. ONE notification.
      _                 <- server.callTool(CallToolRequest("flip", Json.obj()))
      _                 <- IO.sleep(100.millis)
      // 1 → 2: still visible, set didn't change. ZERO notifications.
      _                 <- server.callTool(CallToolRequest("flip", Json.obj()))
      _                 <- IO.sleep(100.millis)
      methods           <- fiber.joinWithNever
      _                 <- cleanup
    yield assertEquals(methods, List("notifications/tools/list_changed"))
  }

  test(".state mutation that doesn't change visibility emits no notifications") {
    val builder = McpHttp
      .streaming[IO]
      .name("t")
      .version("0")
      .state[Int](_ => IO.pure(5))
      .withContextualTool(
        new Tool[IO, SignallingRef[IO, Int]]:
          val name                                                                          = "bump"
          val description                                                                   = ""
          val inputSchema                                                                   = Json.obj()
          val outputSchema                                                                  = None
          def handle(call: ToolCallContext[IO, SignallingRef[IO, Int]]): IO[CallToolResponse] =
            call.ctx.update(_ + 1).as(CallToolResponse(List(Content.Text("ok")), None, false))
      )
      .withContextualTool(
        passthrough[SignallingRef[IO, Int]]("conditional", Some(_.get.map(_ > 0))) // always true here
      )

    for
      sc                <- session
      (ctx, cc)          = sc
      factoryOut        <- builder.newSessionFactory("sid")(ctx)
      (_, cleanup)       = factoryOut
      fiber             <- collectNotifications(cc, expected = 1, window = 500.millis).start
      _                 <- IO.sleep(50.millis)
      // 5 → 6 → 7: visibility stays true throughout, so the watcher should publish nothing.
      _                 <- factoryOut._1.callTool(CallToolRequest("bump", Json.obj()))
      _                 <- factoryOut._1.callTool(CallToolRequest("bump", Json.obj()))
      methods           <- fiber.joinWithNever
      _                 <- cleanup
    yield assertEquals(methods, Nil)
  }

  test(".context-only server publishes no tools/list_changed (no watcher fiber)") {
    val builder = McpHttp
      .streaming[IO]
      .name("t")
      .version("0")
      .context[String](_ => IO.pure("dep"))
      .withContextualTool(passthrough[String]("always"))

    for
      sc                <- session
      (ctx, cc)          = sc
      factoryOut        <- builder.newSessionFactory("sid")(ctx)
      (server, cleanup)  = factoryOut
      fiber             <- collectNotifications(cc, expected = 1, window = 500.millis).start
      _                 <- IO.sleep(50.millis)
      _                 <- server.listTools(ListToolsRequest()) // no signals to fire even with mutation-ish activity
      methods           <- fiber.joinWithNever
      _                 <- cleanup
    yield assertEquals(methods, Nil)
  }

  test(".state auto-enables the tools/listChanged capability") {
    val builder = McpHttp
      .streaming[IO]
      .name("t")
      .version("0")
      .state[Int](_ => IO.pure(0))

    builder.buildServer.map { server =>
      assertEquals(
        server.capabilities.tools.flatMap(_.listChanged),
        Some(true)
      )
    }
  }

  test(".context alone does NOT auto-enable tools/listChanged") {
    val builder = McpHttp
      .streaming[IO]
      .name("t")
      .version("0")
      .context[String](_ => IO.pure("dep"))

    builder.buildServer.map { server =>
      assertEquals(
        server.capabilities.tools.flatMap(_.listChanged),
        None
      )
    }
  }
