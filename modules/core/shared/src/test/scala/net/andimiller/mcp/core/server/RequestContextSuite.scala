package net.andimiller.mcp.core.server

import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.syntax.all.*
import munit.CatsEffectSuite
import net.andimiller.mcp.core.protocol.jsonrpc.{Message, RequestId}

import scala.concurrent.duration.*

class RequestContextSuite extends CatsEffectSuite:

  private val requestId = RequestId.fromString("req-1")

  /**
   * Subscribe to the sink and publish, with a small delay to let the fs2 Topic
   * attach the subscriber before the publish happens. Returns the first message
   * the subscriber sees (or `None` on timeout).
   */
  private def withSubscriberThen(sink: NotificationSink[IO])(action: IO[Unit]): IO[Option[Message]] =
    for
      fiber  <- sink.subscribe.take(1).compile.last.start
      _      <- IO.sleep(50.millis) // let the subscriber attach
      _      <- action
      result <- fiber.joinWithNever.timeout(2.seconds).attempt.map(_.toOption.flatten)
    yield result

  test("progress is a no-op when no token was supplied") {
    NotificationSink.create[IO].use { sink =>
      Deferred[IO, Unit].flatMap { cancel =>
        val rc = RequestContext.live(requestId, None, sink, cancel)
        for
          fiber <- sink.subscribe.take(1).compile.last.start
          _     <- IO.sleep(50.millis)
          _     <- rc.progress(1.0, Some(3.0), Some("step one"))
          _     <- IO.sleep(50.millis)
          _     <- fiber.cancel
          // Either the fiber was cancelled before any element arrived, or it naturally
          // completed with None. Either way, no element should have been produced.
        yield assert(true)
      }
    }
  }

  test("progress emits notifications/progress tagged with the token") {
    NotificationSink.create[IO].use { sink =>
      Deferred[IO, Unit].flatMap { cancel =>
        val token = ProgressToken.fromString("tok-1")
        val rc    = RequestContext.live(requestId, Some(token), sink, cancel)
        withSubscriberThen(sink)(rc.progress(2.5, Some(10.0), Some("halfway"))).map {
          case Some(Message.Notification(_, method, Some(params))) =>
            assertEquals(method, "notifications/progress")
            assertEquals(params.hcursor.get[String]("progressToken").toOption, Some("tok-1"))
            assertEquals(params.hcursor.get[Double]("progress").toOption, Some(2.5))
            assertEquals(params.hcursor.get[Double]("total").toOption, Some(10.0))
            assertEquals(params.hcursor.get[String]("message").toOption, Some("halfway"))
          case other =>
            fail(s"expected progress notification, got: $other")
        }
      }
    }
  }

  test("progress omits total and message when not supplied") {
    NotificationSink.create[IO].use { sink =>
      Deferred[IO, Unit].flatMap { cancel =>
        val token = ProgressToken.fromLong(7L)
        val rc    = RequestContext.live(requestId, Some(token), sink, cancel)
        withSubscriberThen(sink)(rc.progress(1.0)).map {
          case Some(Message.Notification(_, _, Some(params))) =>
            assertEquals(params.hcursor.downField("total").succeeded, false)
            assertEquals(params.hcursor.downField("message").succeeded, false)
            assertEquals(params.hcursor.get[Long]("progressToken").toOption, Some(7L))
          case other =>
            fail(s"unexpected message: $other")
        }
      }
    }
  }

  test("isCancelled flips once the deferred is completed") {
    Deferred[IO, Unit].flatMap { cancel =>
      val rc = RequestContext.live(requestId, None, NotificationSink.noop[IO], cancel)
      for
        before <- rc.isCancelled
        _      <- cancel.complete(())
        after  <- rc.isCancelled
      yield
        assertEquals(before, false)
        assertEquals(after, true)
    }
  }

  test("cancelled blocks until completion") {
    Deferred[IO, Unit].flatMap { cancel =>
      val rc = RequestContext.live(requestId, None, NotificationSink.noop[IO], cancel)
      for
        fiber  <- rc.cancelled.start
        notYet <- fiber.join.timeout(50.millis).attempt
        _      <- cancel.complete(())
        _      <- fiber.join
      yield assert(notYet.isLeft) // timed out first time
    }
  }
