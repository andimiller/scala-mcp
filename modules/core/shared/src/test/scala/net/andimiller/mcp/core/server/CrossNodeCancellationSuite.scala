package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.kernel.{Deferred, Resource}
import cats.syntax.all.*
import fs2.concurrent.Topic
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import munit.CatsEffectSuite
import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import scala.concurrent.duration.*

/**
 * Proves the distributed semantics required by the multi-node cancellation design:
 * a `cancel(id)` issued on "node B" interrupts a `register(id)` held on "node A",
 * routed through a shared broadcast channel.
 *
 * The broadcast is modeled with an in-memory `fs2.concurrent.Topic` standing in
 * for Redis pub/sub. Each node hosts a `CancellationRegistry[F]` whose `cancel`
 * publishes to the topic, and whose background subscriber completes local
 * Deferreds when matching broadcasts arrive.
 */
class CrossNodeCancellationSuite extends CatsEffectSuite:

  /** Payload carried on the shared topic — mirrors the Redis implementation's wire format. */
  private case class Broadcast(requestId: RequestId)
  private object Broadcast:
    given Encoder[Broadcast] = Encoder.instance(b => Json.obj("requestId" -> b.requestId.asJson))
    given Decoder[Broadcast] = Decoder.instance(_.get[RequestId]("requestId").map(Broadcast.apply))

  /** Build a broadcast-backed registry sharing the given topic. */
  private def broadcastRegistry(topic: Topic[IO, String]): Resource[IO, CancellationRegistry[IO]] =
    for
      local  <- Resource.eval(CancellationRegistry.create[IO])
      stream <- topic.subscribeAwait(16)
      _      <- stream
                  .evalMap(raw => decode[Broadcast](raw).toOption
                    .fold(IO.unit)(b => local.cancel(b.requestId)))
                  .compile
                  .drain
                  .background
    yield new CancellationRegistry[IO]:
      def register(id: RequestId)  = local.register(id)
      def complete(id: RequestId)  = local.complete(id)
      def isActive(id: RequestId)  = local.isActive(id)
      def cancel(id: RequestId)    = topic.publish1(Broadcast(id).asJson.noSpaces).void

  test("cancel on node B completes a register on node A") {
    Topic[IO, String].flatMap { topic =>
      (broadcastRegistry(topic), broadcastRegistry(topic)).tupled.use { (nodeA, nodeB) =>
        val id = RequestId.fromLong(1L)
        for
          deferred <- nodeA.register(id)
          _        <- nodeB.cancel(id)
          // Cancel is async across "nodes" — wait for the local Deferred to flip.
          _        <- deferred.get.timeout(2.seconds)
        yield ()
      }
    }
  }

  test("cancel doesn't affect a different request id on the same node") {
    Topic[IO, String].flatMap { topic =>
      broadcastRegistry(topic).use { node =>
        for
          d1     <- node.register(RequestId.fromLong(1L))
          d2     <- node.register(RequestId.fromLong(2L))
          _      <- node.cancel(RequestId.fromLong(1L))
          _      <- d1.get.timeout(2.seconds)
          // d2 should not be completed
          notYet <- d2.tryGet
        yield assertEquals(notYet, None)
      }
    }
  }

  test("register on B is not cancelled by cancel on A if registration is on B") {
    // Each Deferred lives on exactly one node. cancel on A broadcasts, B's
    // subscriber sees it and completes B's Deferred. Verifies the subscriber
    // path, not just that node A happens to have a local match.
    Topic[IO, String].flatMap { topic =>
      (broadcastRegistry(topic), broadcastRegistry(topic)).tupled.use { (nodeA, nodeB) =>
        val id = RequestId.fromString("xyz")
        for
          dB <- nodeB.register(id)
          _  <- nodeA.cancel(id) // A has no local registration for id, only B does
          _  <- dB.get.timeout(2.seconds)
        yield ()
      }
    }
  }
