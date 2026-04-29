package net.andimiller.mcp.core.server

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.std.Queue

import net.andimiller.mcp.core.protocol.ClientCapabilities
import net.andimiller.mcp.core.protocol.ElicitationCapabilities
import net.andimiller.mcp.core.protocol.FormElicitationCapability
import net.andimiller.mcp.core.protocol.jsonrpc.JsonRpcError
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

class ServerRequesterSuite extends CatsEffectSuite:

  test("request publishes a Message.Request and resolves once completeResponse fires") {
    for
      published     <- Queue.unbounded[IO, Message]
      requester     <- ServerRequester.create[IO](published.offer)
      fib           <- requester.request("ping", Some(Json.obj("hello" -> "world".asJson))).start
      msg           <- published.take.timeout(2.seconds)
      requestMessage = msg match
                         case r: Message.Request => r
                         case other              => fail(s"expected Request, got $other")
      _      <- requester.completeResponse(requestMessage.id, Right(Json.obj("ok" -> true.asJson)))
      result <- fib.joinWithNever
    yield
      assertEquals(requestMessage.method, "ping")
      assertEquals(requestMessage.params, Some(Json.obj("hello" -> "world".asJson)))
      assertEquals(result, Right(Json.obj("ok" -> true.asJson)))
  }

  test("request returns a timeout error when no response arrives in time") {
    for
      requester <- ServerRequester.create[IO](_ => IO.unit)
      result    <- requester.request("ping", None, Some(50.millis))
    yield result match
      case Left(err) =>
        assertEquals(err.code, ServerRequester.TimeoutErrorCode)
      case Right(j) =>
        fail(s"expected timeout, got $j")
  }

  test("setClientCapabilities is reflected in clientCapabilities") {
    val caps = ClientCapabilities(elicitation = Some(ElicitationCapabilities(form = Some(FormElicitationCapability()))))
    for
      requester <- ServerRequester.create[IO](_ => IO.unit)
      before    <- requester.clientCapabilities
      _         <- requester.setClientCapabilities(caps)
      after     <- requester.clientCapabilities
    yield
      assertEquals(before, None)
      assertEquals(after, Some(caps))
  }

  test("completeResponse with no matching pending id is a no-op") {
    for
      requester <- ServerRequester.create[IO](_ => IO.unit)
      _         <- requester.completeResponse(RequestId.fromString("does-not-exist"), Right(Json.obj()))
    yield ()
  }

  test("error responses propagate as Left(JsonRpcError)") {
    for
      published <- Queue.unbounded[IO, Message]
      requester <- ServerRequester.create[IO](published.offer)
      fib       <- requester.request("ping", None).start
      msg       <- published.take.timeout(2.seconds)
      reqMessage = msg.asInstanceOf[Message.Request]
      err        = JsonRpcError(-32000, "boom", None)
      _         <- requester.completeResponse(reqMessage.id, Left(err))
      result    <- fib.joinWithNever
    yield assertEquals(result, Left(err))
  }
