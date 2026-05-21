package net.andimiller.mcp.http4s

import scala.concurrent.duration.*

import cats.Eq
import cats.effect.IO
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.state.SessionRefs

import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*

class StreamingMcpHttpBuilderAuthSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 10.seconds

  case class User(name: String, isAdmin: Boolean)

  given Eq[User] = Eq.fromUniversalEquals

  /** Tool whose handler just echoes its name as text. `Ctx` left as a free type param so the same helper works against
    * `Tool[IO, Unit]` or `Tool[IO, User]`.
    */
  private def echoTool[C](n: String): Tool[IO, C] = new Tool[IO, C]:
    val name                                                       = n
    val description                                                = s"tool $n"
    val inputSchema                                                = Json.obj()
    val outputSchema                                               = None
    def handle(call: ToolCallContext[IO, C]): IO[CallToolResponse] =
      IO.pure(CallToolResponse(List(Content.Text(n)), None, false))

  /** Build a per-session Server[IO] for the given authenticated user, then list the tools it exposes. */
  private def listToolsFor(
      builder: StreamingMcpHttpBuilder[IO, User, User],
      user: User
  ): IO[Set[String]] =
    for
      cc           <- ClientChannel.noop[IO]
      ctx           = SessionContext[IO]("test-session", cc, SessionRefs.inMemory[IO])
      server       <- builder.newAuthenticatedSessionFactory("test-session")(user, ctx)
      listResponse <- server.listTools(ListToolsRequest())
    yield listResponse.tools.map(_.name).toSet

  private def callTool(
      builder: StreamingMcpHttpBuilder[IO, User, User],
      user: User,
      name: String
  ): IO[CallToolResponse] =
    for
      cc     <- ClientChannel.noop[IO]
      ctx     = SessionContext[IO]("test-session", cc, SessionRefs.inMemory[IO])
      server <- builder.newAuthenticatedSessionFactory("test-session")(user, ctx)
      resp   <- server.callTool(CallToolRequest(name, Json.obj()))
    yield resp

  private def authBuilder: StreamingMcpHttpBuilder[IO, User, User] =
    McpHttp
      .streaming[IO]
      .name("test")
      .version("0")
      .authenticated[User](
        extract = _ => IO.pure(None),
        onUnauthorized = Response[IO](Status.Unauthorized)
      )

  // ── Tests ──────────────────────────────────────────────────────────

  test("withToolIf hides the gated tool from users who fail the predicate") {
    val admin   = User("alice", isAdmin = true)
    val guest   = User("bob", isAdmin = false)
    val builder = authBuilder
      .withTool(echoTool[User]("public"))
      .withToolIf((u: User) => u.isAdmin)(echoTool[User]("admin_only"))

    for
      adminTools <- listToolsFor(builder, admin)
      guestTools <- listToolsFor(builder, guest)
    yield
      assertEquals(adminTools, Set("public", "admin_only"), "admin sees both tools")
      assertEquals(guestTools, Set("public"), "guest sees only the ungated tool")
  }

  test("calling a hidden tool returns the unknown-tool error path (DefaultServer raises 'Tool not found')") {
    val guest   = User("bob", isAdmin = false)
    val builder = authBuilder
      .withToolIf((u: User) => u.isAdmin)(echoTool[User]("admin_only"))

    callTool(builder, guest, "admin_only").attempt.map {
      case Left(err) =>
        assert(
          err.getMessage.contains("Tool not found"),
          s"expected 'Tool not found' error, got: ${err.getMessage}"
        )
      case Right(resp) =>
        fail(s"expected the call to fail with unknown tool, got success: $resp")
    }
  }

  test("withToolIfF supports effectful predicates") {
    val admin   = User("alice", isAdmin = true)
    val guest   = User("bob", isAdmin = false)
    val builder = authBuilder
      .withToolIfF((u: User) => IO.pure(u.isAdmin))(echoTool[User]("admin_only"))

    for
      adminTools <- listToolsFor(builder, admin)
      guestTools <- listToolsFor(builder, guest)
    yield
      assertEquals(adminTools, Set("admin_only"))
      assertEquals(guestTools, Set.empty[String])
  }

  test("withContextualToolIf gates contextual tools the same way") {
    val admin   = User("alice", isAdmin = true)
    val guest   = User("bob", isAdmin = false)
    val builder = authBuilder
      .withContextualTool(echoTool[User]("always_ctx"))
      .withContextualToolIf((u: User) => u.isAdmin)(echoTool[User]("admin_ctx"))

    for
      adminTools <- listToolsFor(builder, admin)
      guestTools <- listToolsFor(builder, guest)
    yield
      assertEquals(adminTools, Set("always_ctx", "admin_ctx"))
      assertEquals(guestTools, Set("always_ctx"))
  }

  test("withToolIf is a compile error without .authenticated[U] (A =:= Unit)") {
    // The NotGiven[A =:= Unit] evidence on withToolIf rejects calls before .authenticated[U].
    // We verify that with munit's compileErrors macro.
    val errors = compileErrors(
      """McpHttp.streaming[IO].withToolIf((_: User) => true)(echoTool[Unit]("x"))"""
    )
    assert(errors.nonEmpty, "expected withToolIf on an unauth'd builder to be a compile error")
  }

  test("hasPredicatedTools is true after withToolIf") {
    val builder = authBuilder.withToolIf((u: User) => u.isAdmin)(echoTool[User]("admin_only"))
    assertEquals(builder.hasPredicatedTools, true)
  }

  test("hasPredicatedTools is false for ungated registrations") {
    val builder = authBuilder.withTool(echoTool[User]("public"))
    assertEquals(builder.hasPredicatedTools, false)
  }
