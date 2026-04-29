package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.schema.JsonSchema

import io.circe.Codec
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

class ElicitationClientSuite extends CatsEffectSuite:

  case class Form(value: String) derives JsonSchema, Codec.AsObject

  private val capsWithForm =
    ClientCapabilities(elicitation = Some(ElicitationCapabilities(form = Some(FormElicitationCapability()))))

  private def autoRespond(envelope: ElicitationCreateResponse): IO[(Ref[IO, Vector[Message]], ElicitationClient[IO])] =
    for
      published   <- Ref.of[IO, Vector[Message]](Vector.empty)
      requestSeen <- Deferred[IO, Message.Request]
      requester   <- ServerRequester.create[IO] { msg =>
                     published.update(_ :+ msg) *> (msg match
                       case r: Message.Request => requestSeen.complete(r).void
                       case _                  => IO.unit)
                   }
      _ <- requester.setClientCapabilities(capsWithForm)
      _ <- requestSeen.get
             .flatMap(r => requester.completeResponse(r.id, Right(envelope.asJson)))
             .start
    yield (published, ElicitationClient.fromRequester(requester))

  test("requestForm with accept returns Accept(decoded)") {
    val response = ElicitationCreateResponse(
      action = ElicitAction.accept,
      content = Some(Json.obj("value" -> "hello".asJson))
    )
    for
      pair       <- autoRespond(response)
      (_, client) = pair
      result     <- client.requestForm[Form](message = "fill me", timeout = None)
    yield assertEquals(result, Right(ElicitResult.Accept(Form("hello"))))
  }

  test("requestForm with decline returns Decline") {
    val response = ElicitationCreateResponse(action = ElicitAction.decline)
    for
      pair       <- autoRespond(response)
      (_, client) = pair
      result     <- client.requestForm[Form](message = "fill me", timeout = None)
    yield assertEquals(result, Right(ElicitResult.Decline))
  }

  test("requestForm with cancel returns Cancel") {
    val response = ElicitationCreateResponse(action = ElicitAction.cancel)
    for
      pair       <- autoRespond(response)
      (_, client) = pair
      result     <- client.requestForm[Form](message = "fill me", timeout = None)
    yield assertEquals(result, Right(ElicitResult.Cancel))
  }

  test("requestForm without form capability returns CapabilityMissing") {
    for
      published <- Ref.of[IO, Vector[Message]](Vector.empty)
      requester <- ServerRequester.create[IO](msg => published.update(_ :+ msg))
      // no setClientCapabilities call -> capabilities are None
      client  = ElicitationClient.fromRequester(requester)
      result <- client.requestForm[Form](message = "fill me", timeout = None)
      pubs   <- published.get
    yield
      assertEquals(result, Left(ElicitationError.CapabilityMissing))
      assertEquals(pubs, Vector.empty)
  }

  test("requestForm with malformed accept content returns Decode error") {
    val response = ElicitationCreateResponse(
      action = ElicitAction.accept,
      content = Some(Json.obj("wrong" -> 42.asJson))
    )
    for
      pair       <- autoRespond(response)
      (_, client) = pair
      result     <- client.requestForm[Form](message = "fill me", timeout = None)
    yield result match
      case Left(_: ElicitationError.Decode) => ()
      case other                            => fail(s"expected Decode error, got $other")
  }
