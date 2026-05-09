package net.andimiller.mcp.http4s

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.Resource

import net.andimiller.mcp.core.client.McpClient
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.server.DefaultServer
import net.andimiller.mcp.core.server.Tool

import com.comcast.ip4s.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

/** End-to-end test: real http4s server + real ember-client wired through `StreamableHttpMcpClient`. */
class StreamableHttpMcpClientSuite extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def echoTool: Tool.Resolved[IO] = new Tool.Resolved[IO]:
    val name                                          = "echo"
    val description                                   = "Echo back the args"
    val inputSchema                                   = Json.obj("type" -> "object".asJson)
    val outputSchema                                  = None
    def handle(arguments: Json): IO[CallToolResponse] =
      IO.pure(
        CallToolResponse(
          content = List(Content.Text(s"echo:${arguments.noSpaces}")),
          structuredContent = None,
          isError = false
        )
      )

  private def runHarness[A](use: McpClient[IO] => IO[A]): IO[A] =
    val resource =
      for
        server <- Resource.eval(
                    DefaultServer[IO](
                      info = Implementation("test-server", "1.0"),
                      capabilities = ServerCapabilities.withTools(),
                      toolHandlers = List(echoTool)
                    )
                  )
        routes      <- StreamableHttpTransport.routes[IO]((_, _) => IO.pure(server))
        emberServer <- EmberServerBuilder
                         .default[IO]
                         .withHost(host"127.0.0.1")
                         .withPort(port"0")
                         .withHttpApp(routes.orNotFound)
                         .withShutdownTimeout(1.second)
                         .build
        port        = emberServer.address.getPort
        baseUri     = Uri.unsafeFromString(s"http://127.0.0.1:$port/mcp")
        httpClient <- EmberClientBuilder.default[IO].build
        client     <- StreamableHttpMcpClient
                    .builder[IO](httpClient, baseUri)
                    .withInfo(Implementation("test-client", "0.1.0"))
                    .connect
      yield client
    resource.use(use)

  test("end-to-end: initialize, listTools, callTool, ping over real HTTP") {
    runHarness { client =>
      for
        tools <- client.listTools()
        res   <- client.callTool("echo", Json.obj("hello" -> "world".asJson))
        _     <- client.ping()
      yield
        assertEquals(client.serverInfo.name, "test-server")
        assertEquals(client.protocolVersion, "2025-11-25")
        assertEquals(tools.tools.map(_.name), List("echo"))
        assertEquals(res.isError, false)
        assertEquals(
          res.content.headOption,
          Some(Content.Text("""echo:{"hello":"world"}"""))
        )
    }
  }

  test("connecting without info raises") {
    val program = (for
      server <- Resource.eval(
                  DefaultServer[IO](
                    info = Implementation("s", "1"),
                    capabilities = ServerCapabilities.empty
                  )
                )
      routes <- StreamableHttpTransport.routes[IO]((_, _) => IO.pure(server))
      es     <- EmberServerBuilder
              .default[IO]
              .withHost(host"127.0.0.1")
              .withPort(port"0")
              .withHttpApp(routes.orNotFound)
              .build
      hc <- EmberClientBuilder.default[IO].build
      _  <- StreamableHttpMcpClient
             .builder[IO](hc, Uri.unsafeFromString(s"http://127.0.0.1:${es.address.getPort}/mcp"))
             // intentionally no withInfo
             .connect
    yield ()).use_

    program.attempt.map { e =>
      assert(e.isLeft, "expected failure when info is missing")
      e.left.toOption.foreach(t => assert(t.isInstanceOf[IllegalStateException]))
    }
  }
