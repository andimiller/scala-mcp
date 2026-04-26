package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import net.andimiller.mcp.core.protocol.{ClientCapabilities, ElicitationCapabilities, FormElicitationCapability}
import net.andimiller.mcp.core.protocol.jsonrpc.{JsonRpcError, Message, RequestId}

import scala.concurrent.duration.*

class ServerRequesterSuite extends CatsEffectSuite:

  test("request publishes a Message.Request and resolves once completeResponse fires") {
    for
      published <- Ref.of[IO, Vector[Message]](Vector.empty)
      requester <- ServerRequester.create[IO](msg => published.update(_ :+ msg))
      // launch the request in the background; we'll complete it by hand below
      fib <- requester.request("ping", Some(Json.obj("hello" -> "world".asJson))).start
      // wait for the request to be published
      _ <- IO.cede *> IO.sleep(20.millis)
      msgs <- published.get
      requestMessage = msgs.head match
        case r: Message.Request => r
        case other              => fail(s"expected Request, got $other")
      _ <- requester.completeResponse(requestMessage.id, Right(Json.obj("ok" -> true.asJson)))
      result <- fib.joinWithNever
    yield
      assertEquals(requestMessage.method, "ping")
      assertEquals(requestMessage.params, Some(Json.obj("hello" -> "world".asJson)))
      assertEquals(result, Right(Json.obj("ok" -> true.asJson)))
  }

  test("request returns a timeout error when no response arrives in time") {
    for
      published <- Ref.of[IO, Vector[Message]](Vector.empty)
      requester <- ServerRequester.create[IO](msg => published.update(_ :+ msg))
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
      published <- Ref.of[IO, Vector[Message]](Vector.empty)
      requester <- ServerRequester.create[IO](msg => published.update(_ :+ msg))
      before    <- requester.clientCapabilities
      _         <- requester.setClientCapabilities(caps)
      after     <- requester.clientCapabilities
    yield
      assertEquals(before, None)
      assertEquals(after, Some(caps))
  }

  test("completeResponse with no matching pending id is a no-op") {
    for
      published <- Ref.of[IO, Vector[Message]](Vector.empty)
      requester <- ServerRequester.create[IO](msg => published.update(_ :+ msg))
      _         <- requester.completeResponse(RequestId.fromString("does-not-exist"), Right(Json.obj()))
    yield ()
  }

  test("error responses propagate as Left(JsonRpcError)") {
    for
      published <- Ref.of[IO, Vector[Message]](Vector.empty)
      requester <- ServerRequester.create[IO](msg => published.update(_ :+ msg))
      fib       <- requester.request("ping", None).start
      _         <- IO.cede *> IO.sleep(20.millis)
      msgs      <- published.get
      reqMessage = msgs.head.asInstanceOf[Message.Request]
      err        = JsonRpcError(-32000, "boom", None)
      _         <- requester.completeResponse(reqMessage.id, Left(err))
      result    <- fib.joinWithNever
    yield assertEquals(result, Left(err))
  }
