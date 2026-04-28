package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.kernel.Deferred
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import scala.concurrent.duration.*

class RequestHandlerCancellationSuite extends CatsEffectSuite:

  private def gatedTool(gate: Deferred[IO, Unit]): Tool.Resolved[IO] =
    new Tool.Resolved[IO]:
      val name                                          = "slow"
      val description                                   = "blocks until gate completes"
      val inputSchema                                   = Json.obj()
      val outputSchema                                  = None
      def handle(arguments: Json): IO[CallToolResponse] =
        gate.get.as(CallToolResponse(List(Content.Text("done")), None, false))

  private def buildHandler(gate: Deferred[IO, Unit]): IO[RequestHandler[IO]] =
    for
      server <- DefaultServer[IO](
                  info = Implementation("test", "0"),
                  capabilities = ServerCapabilities(),
                  toolHandlers = List(gatedTool(gate))
                )
      requester <- ServerRequester.noop[IO]
      cancel    <- CancellationRegistry.create[IO]
    yield new RequestHandler[IO](server, requester, cancel)

  test("tools/call followed by notifications/cancelled returns None (no response)") {
    val id          = RequestId.fromLong(7L)
    val callRequest =
      Message.request(id, "tools/call", Some(CallToolRequest("slow", Json.obj()).asJson))
    val cancelMsg =
      Message.notification("notifications/cancelled", Some(CancelledNotificationParams(id, None).asJson))

    for
      gate    <- Deferred[IO, Unit]
      handler <- buildHandler(gate)
      callFib <- handler.handle(callRequest).start
      _       <- IO.sleep(100.millis) // let the call register in the cancel registry
      _       <- handler.handle(cancelMsg)
      result  <- callFib.joinWithNever
    yield assertEquals(result, None)
  }

  test("cancel for unknown id does not affect a concurrent in-flight call") {
    val activeId    = RequestId.fromLong(1L)
    val unknownId   = RequestId.fromLong(99L)
    val callRequest =
      Message.request(activeId, "tools/call", Some(CallToolRequest("slow", Json.obj()).asJson))
    val cancelMsg =
      Message.notification("notifications/cancelled", Some(CancelledNotificationParams(unknownId, None).asJson))

    for
      gate    <- Deferred[IO, Unit]
      handler <- buildHandler(gate)
      callFib <- handler.handle(callRequest).start
      _       <- IO.sleep(100.millis)
      _       <- handler.handle(cancelMsg) // unknown id — should be a no-op
      _       <- gate.complete(())         // let the real call finish
      result  <- callFib.joinWithNever
    yield result match
      case Some(_: Message.Response) => ()
      case other                     => fail(s"expected Some(Response), got $other")
  }
