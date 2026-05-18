package net.andimiller.mcp.trace4cats

import cats.effect.IO
import cats.effect.IOLocal
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.CallToolRequest
import net.andimiller.mcp.core.protocol.CallToolResponse
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.server.Tool
import net.andimiller.mcp.core.server.ToolCallContext

import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import trace4cats.AttributeValue
import trace4cats.EntryPoint
import trace4cats.RefSpanCompleter
import trace4cats.SpanSampler
import trace4cats.SpanStatus
import trace4cats.Trace
import trace4cats.iolocal.ioLocalTrace
import trace4cats.kernel.Span
import trace4cats.model.CompletedSpan

class TraceToolMiddlewareSuite extends CatsEffectSuite:

  /** Stand up a fully-functional `Trace[IO]` for a single test body. Spans go to an in-memory `RefSpanCompleter` and
    * are returned alongside whatever the body produces. The setup uses `ioLocalTrace` so the middleware sees a real
    * (not faked) trace4cats Trace instance.
    */
  private def traced[A](body: Trace[IO] ?=> IO[A]): IO[(A, List[CompletedSpan])] =
    for
      completer <- RefSpanCompleter[IO]("mcp-trace4cats-test")
      ep         = EntryPoint[IO](SpanSampler.always[IO], completer)
      result    <- ep.root("test-root").use { root =>
                  IOLocal[Span[IO]](root).flatMap { local =>
                    given Trace[IO] = ioLocalTrace(local)
                    body
                  }
                }
      spans <- completer.get
    yield (result, spans.toList)

  /** Synthesise a `ToolCallContext` over a request, using a minimal fake `Tool`. Tests don't exercise
    * `context.resolved` heavily — this stub is just to give the middleware *something* to thread through.
    */
  private def ctxOf(req: CallToolRequest, sessionId: Option[String] = None): ToolCallContext[IO, Unit] =
    val resolved = new Tool[IO, Unit]:
      def name                                                          = req.name
      def description                                                   = req.name
      def inputSchema                                                   = Json.obj()
      def outputSchema                                                  = None
      def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] = IO.pure(CallToolResponse(Nil))
    ToolCallContext(req, sessionId, resolved, ())

  private val successHandler: ToolCallContext[IO, Unit] => IO[CallToolResponse] =
    _ => IO.pure(CallToolResponse(List(Content.Text("ok"))))

  private val errorHandler: ToolCallContext[IO, Unit] => IO[CallToolResponse] =
    _ => IO.pure(CallToolResponse(List(Content.Text("boom")), isError = true))

  test("wraps the handler call in a span named 'mcp.tools.call <name>'") {
    traced {
      val mw = TraceToolMiddleware[IO, Unit]
      mw(successHandler)(ctxOf(CallToolRequest("echo", Json.obj())))
    }.map { case (_, spans) =>
      assert(spans.exists(_.name === "mcp.tools.call echo"), s"got: ${spans.map(_.name)}")
    }
  }

  test("records mcp.tool.name + is_error=false + Ok status for a successful response") {
    traced {
      val mw = TraceToolMiddleware[IO, Unit]
      mw(successHandler)(ctxOf(CallToolRequest("echo", Json.obj())))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("missing span"))
      assertEquals(toolSpan.attributes.get("mcp.tool.name"), Some(AttributeValue.StringValue("echo")))
      assertEquals(toolSpan.attributes.get("mcp.tool.is_error"), Some(AttributeValue.BooleanValue(false)))
      assertEquals(toolSpan.status, SpanStatus.Ok)
    }
  }

  test("records mcp.session.id when context carries a sessionId") {
    traced {
      val mw = TraceToolMiddleware[IO, Unit]
      mw(successHandler)(ctxOf(CallToolRequest("echo", Json.obj()), sessionId = Some("sess-1")))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("missing span"))
      assertEquals(toolSpan.attributes.get("mcp.session.id"), Some(AttributeValue.StringValue("sess-1")))
    }
  }

  test("does NOT record mcp.session.id when context has no sessionId") {
    traced {
      val mw = TraceToolMiddleware[IO, Unit]
      mw(successHandler)(ctxOf(CallToolRequest("echo", Json.obj()), sessionId = None))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("missing span"))
      assertEquals(toolSpan.attributes.get("mcp.session.id"), None)
    }
  }

  test("records Internal status when response.isError = true") {
    traced {
      val mw = TraceToolMiddleware[IO, Unit]
      mw(errorHandler)(ctxOf(CallToolRequest("oops", Json.obj())))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call oops").getOrElse(fail("missing span"))
      assertEquals(toolSpan.attributes.get("mcp.tool.is_error"), Some(AttributeValue.BooleanValue(true)))
      assert(toolSpan.status.isInstanceOf[SpanStatus.Internal], s"expected Internal, got: ${toolSpan.status}")
    }
  }

  test("extractor attributes are attached to the span") {
    val extractor: ToolCallContext[IO, Unit] => IO[Map[String, AttributeValue]] = ctx =>
      val user = ctx.request.arguments.hcursor.get[String]("user").toOption
      IO.pure(user.map(u => Map("mcp.tool.user" -> AttributeValue.StringValue(u))).getOrElse(Map.empty))

    traced {
      val mw = TraceToolMiddleware[IO, Unit](extractor)
      mw(successHandler)(ctxOf(CallToolRequest("echo", Json.obj("user" -> "alice".asJson))))
    }.map { case (_, spans) =>
      val toolSpan = spans.find(_.name === "mcp.tools.call echo").getOrElse(fail("missing span"))
      assertEquals(toolSpan.attributes.get("mcp.tool.user"), Some(AttributeValue.StringValue("alice")))
    }
  }
