package net.andimiller.mcp.redis

import scala.concurrent.duration.*

import cats.Eq
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.syntax.all.*

import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId
import net.andimiller.mcp.core.server.Tool
import net.andimiller.mcp.core.server.ToolCallContext
import net.andimiller.mcp.http4s.McpHttp
import net.andimiller.mcp.http4s.StreamableHttpMcpClient

import com.comcast.ip4s.*
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log.NoOp.given
import dev.profunktor.redis4cats.pubsub.PubSub
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import cats.effect.std.UUIDGen
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.syntax.literals.*
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.testing.TestingLoggerFactory
import org.typelevel.ci.CIStringSyntax

/** Full-stack multi-instance integration test:
  *
  *   - Two http4s ember servers, both wired with `.authenticated[User]` + `McpRedis.configure` against the same Redis.
  *   - One MCP client driving them, with a middleware that flips both target URI and auth headers per request.
  *
  * Exercises the contract that's only observable at the wire level: identity bound at `initialize` time is the identity
  * `validateSession` compares against on every subsequent request, so drift (a user whose admin flag flipped between
  * requests) is rejected, regardless of which instance and regardless of cache state.
  */
class RedisMultiInstanceHttpSpec extends CatsEffectSuite with TestContainerForAll:

  private given LoggerFactory[IO] = TestingLoggerFactory.atomic[IO]()

  override def munitIOTimeout: FiniteDuration = 60.seconds

  case class User(name: String, isAdmin: Boolean) derives Encoder.AsObject, Decoder

  given Eq[User] = Eq.fromUniversalEquals

  override val containerDef: GenericContainer.Def[GenericContainer] = GenericContainer.Def(
    dockerImage = "redis:7-alpine",
    exposedPorts = Seq(6379),
    waitStrategy = Wait.forListeningPort()
  )

  // ── Test fixture ──────────────────────────────────────────────────

  /** Echo-style tool — handler doesn't matter for these tests; we only inspect tool catalogues. */
  private def tool(n: String): Tool[IO, User] = new Tool[IO, User]:
    val name                                                          = n
    val description                                                   = s"tool $n"
    val inputSchema                                                   = Json.obj()
    val outputSchema                                                  = None
    def handle(call: ToolCallContext[IO, User]): IO[CallToolResponse] =
      IO.pure(CallToolResponse(List(Content.Text(n)), None, false))

  /** Build one configured streaming server (routes + ember). The auth extractor reads `X-User-Name` and `X-User-Admin`
    * from the request — they're set by the client middleware below.
    *
    * Bypasses `.serve` so we can pin `withShutdownTimeout(1.second)` and avoid 30s teardown per server.
    */
  private def buildServer(
      redis: dev.profunktor.redis4cats.RedisCommands[IO, String, String],
      pubSub: dev.profunktor.redis4cats.pubsub.PubSubCommands[IO, [x] =>> fs2.Stream[IO, x], String, String]
  ): Resource[IO, org.http4s.server.Server] =
    val authenticate: Request[IO] => IO[Option[User]] = req =>
      IO.pure {
        for
          name  <- req.headers.get(ci"x-user-name").map(_.head.value)
          admin <- req.headers.get(ci"x-user-admin").map(_.head.value.toBooleanOption).flatten
        yield User(name, admin)
      }
    val builder = McpHttp
      .streaming[IO]
      .name("multi-instance-test")
      .version("0")
      .authenticated[User](authenticate, Response[IO](Status.Unauthorized))
      .withTool(tool("public"))
      .withToolIf((u: User) => u.isAdmin)(tool("admin_only"))
    val configured = McpRedis.configure[IO, User, User](redis, pubSub).apply(builder)
    for
      routes <- configured.routes(using UUIDGen[IO])
      server <- EmberServerBuilder
                  .default[IO]
                  .withHost(host"127.0.0.1")
                  .withPort(port"0")
                  .withHttpApp(routes.orNotFound)
                  .withShutdownTimeout(1.second)
                  .build
    yield server

  /** Middleware: rewrites the URI's scheme/authority/port from a Ref and injects current-user headers from a Ref. Lets
    * the same MCP client target server A or B on demand and lets us flip the authenticated identity mid-session.
    *
    * Also captures the response's `Mcp-Session-Id` header so the test can extract it for raw-HTTP follow-ups (needed
    * because `StreamableHttpMcpClient` does not expose the session id directly).
    */
  private def tunableClient(
      underlying: Client[IO],
      target: Ref[IO, Uri],
      user: Ref[IO, User],
      capturedSid: Ref[IO, Option[String]]
  ): Client[IO] = Client[IO] { req =>
    Resource.eval((target.get, user.get).tupled).flatMap { case (uri, u) =>
      val rewritten = req
        .withUri(uri.copy(path = req.uri.path, query = req.uri.query))
        .putHeaders(
          Header.Raw(ci"x-user-name", u.name),
          Header.Raw(ci"x-user-admin", u.isAdmin.toString)
        )
      underlying.run(rewritten).evalTap { resp =>
        resp.headers.get(ci"mcp-session-id").traverse_(h => capturedSid.set(Some(h.head.value)))
      }
    }
  }

  /** Fixture: spins up Redis-backed servers A and B against the container, returns their base URIs plus a Ref of the
    * "current target" URI and a Ref of the "current user" the middleware should send.
    */
  private case class Fixture(
      uriA: Uri,
      uriB: Uri,
      target: Ref[IO, Uri],
      user: Ref[IO, User],
      capturedSid: Ref[IO, Option[String]],
      httpClient: Client[IO]
  )

  private def fixture(container: GenericContainer): Resource[IO, Fixture] =
    val redisUri = s"redis://${container.host}:${container.mappedPort(6379)}"
    for
      lettuce      <- RedisClient[IO].from(redisUri)
      redis        <- Redis[IO].fromClient(lettuce, RedisCodec.Utf8)
      pubSub       <- PubSub.mkPubSubConnection[IO, String, String](lettuce, RedisCodec.Utf8)
      serverA      <- buildServer(redis, pubSub)
      serverB      <- buildServer(redis, pubSub)
      uriA          = Uri.unsafeFromString(s"http://127.0.0.1:${serverA.address.getPort}/mcp")
      uriB          = Uri.unsafeFromString(s"http://127.0.0.1:${serverB.address.getPort}/mcp")
      target       <- Resource.eval(Ref.of[IO, Uri](uriA))
      currentUser  <- Resource.eval(Ref.of[IO, User](User("alice", isAdmin = true)))
      capturedSid  <- Resource.eval(Ref.of[IO, Option[String]](None))
      rawClient    <- EmberClientBuilder.default[IO].build
      wrappedClient = tunableClient(rawClient, target, currentUser, capturedSid)
    yield Fixture(uriA, uriB, target, currentUser, capturedSid, wrappedClient)

  /** Drive an MCP client through the wrapped http client. The MCP client is built against `uriA` as a placeholder — the
    * middleware rewrites every request to whatever `target` currently holds.
    */
  private def mcpClient(f: Fixture): Resource[IO, net.andimiller.mcp.core.client.McpClient[IO]] =
    StreamableHttpMcpClient
      .builder[IO](f.httpClient, f.uriA)
      .withInfo(Implementation("multi-instance-test-client", "0.1.0"))
      .connect

  // ── Tests ─────────────────────────────────────────────────────────

  test("happy path: init on A, listTools on B — admin tool is visible after cross-instance reconstruct") {
    withContainers { case container: GenericContainer =>
      fixture(container).use { f =>
        // Default state: alice (admin=true), targeting A.
        mcpClient(f).use { client =>
          for
            // Init happened against A during `.connect`. Now switch to B for the listTools call.
            _    <- f.target.set(f.uriB)
            list <- client.listTools()
          yield assertEquals(
            list.tools.map(_.name).toSet,
            Set("public", "admin_only"),
            "B's reconstruct must have used the admin=true identity stored in Redis by A"
          )
        }
      }
    }
  }

  test("drift across instances: init A admin=true, then listTools B admin=false → 403") {
    withContainers { case container: GenericContainer =>
      fixture(container).use { f =>
        mcpClient(f).use { client =>
          for
            // Init done on A with admin=true. Confirm we have a session.
            _   <- client.listTools()
            sid <- f.capturedSid.get.map(_.getOrElse(fail("session id should have been captured")))
            // Flip identity + retarget to B, then make a raw tools/list POST so we can inspect status precisely.
            _    <- f.user.set(User("alice", isAdmin = false))
            _    <- f.target.set(f.uriB)
            req   = rawToolsListRequest(f.uriB, sid)
            resp <- f.httpClient.run(req).use(r => IO.pure(r.status))
          yield assertEquals(resp, Status.Forbidden, "B must reject a drifted identity with 403")
        }
      }
    }
  }

  test("drift on the same instance: A still rejects the drifted identity (cached session, Redis-stored user)") {
    withContainers { case container: GenericContainer =>
      fixture(container).use { f =>
        mcpClient(f).use { client =>
          for
            _   <- client.listTools()
            sid <- f.capturedSid.get.map(_.getOrElse(fail("session id should have been captured")))
            _   <- f.user.set(User("alice", isAdmin = false))
            // Stay on A — its local cache has the live session — but validateSession should still consult Redis.
            req   = rawToolsListRequest(f.uriA, sid)
            resp <- f.httpClient.run(req).use(r => IO.pure(r.status))
          yield assertEquals(resp, Status.Forbidden, "A must reject the drifted identity even with the session cached")
        }
      }
    }
  }

  /** Hand-build a JSON-RPC tools/list POST. Going around the MCP client lets us check the response status precisely.
    * The session id and auth headers are wired through the middleware (target Uri + user Ref).
    */
  private def rawToolsListRequest(base: Uri, sessionId: String): Request[IO] =
    val body = Message.request(RequestId.fromLong(99), "tools/list", None).asJson.noSpaces
    Request[IO](Method.POST, base)
      .withEntity(body)
      .putHeaders(
        Header.Raw(ci"mcp-session-id", sessionId),
        Header.Raw(ci"content-type", "application/json")
      )
