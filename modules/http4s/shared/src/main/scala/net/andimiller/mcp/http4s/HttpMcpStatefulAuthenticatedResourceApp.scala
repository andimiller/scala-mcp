package net.andimiller.mcp.http4s

import cats.Eq
import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.*
import net.andimiller.mcp.core.server.*
import org.http4s.{Header, Request, Response, Status}
import org.typelevel.ci.*

/**
 * Convenience trait for building authenticated MCP servers over Streamable HTTP.
 *
 * `R` is shared application state (created once), `S` is per-session state
 * (created for each client session from the shared state, notification sink,
 * and authenticated user identity), and `U` is the user identity type
 * extracted from HTTP requests.
 *
 * Authentication is performed on every request. The user identity extracted
 * from the initial `initialize` request is bound to the session; subsequent
 * requests must present credentials for the same user (enforced via `Eq[U]`).
 *
 * Example:
 * {{{
 * case class UserContext(username: String)
 * object UserContext:
 *   given Eq[UserContext] = Eq.by(_.username)
 *
 * object MyServer extends HttpMcpStatefulAuthenticatedResourceApp[MyServer.Res, MyServer.Session, UserContext]:
 *   def serverName    = "my-server"
 *   def serverVersion = "1.0.0"
 *
 *   def authenticate(request: Request[IO]): IO[Option[UserContext]] =
 *     // extract user from Basic Auth, Bearer token, etc.
 *
 *   def mkResources = Resource.eval(Ref.of[IO, Int](0).map(Res(_)))
 *
 *   def mkSessionResources(r: Res, sink: NotificationSink[IO], user: UserContext) =
 *     IO.pure(Session(r, sink, user))
 *
 *   override def tools(r: Res, s: Session, user: UserContext) = List(...)
 * }}}
 */
trait HttpMcpStatefulAuthenticatedResourceApp[R, S, U: Eq] extends IOApp.Simple:

  /** The name of this MCP server */
  def serverName: String

  /** The version of this MCP server */
  def serverVersion: String

  /** Host to bind to (default: all interfaces) */
  def host: Host = host"0.0.0.0"

  /** Port to listen on (default: 8080) */
  def port: Port = port"8080"

  /** Whether to serve the MCP Explorer UI at /explorer (default: false) */
  def explorerEnabled: Boolean = false

  /** Whether to redirect / to /explorer/index.html (default: false, requires explorerEnabled) */
  def rootRedirectToExplorer: Boolean = false

  /** Transform the http4s server resource after it is built (e.g. to log the bound address) */
  def transformServer(server: Resource[IO, org.http4s.server.Server]): Resource[IO, org.http4s.server.Server] = server

  /** Extract a user identity from the HTTP request. Return `None` to deny access. */
  def authenticate(request: Request[IO]): IO[Option[U]]

  /** Response to return when authentication fails (default: 401 with Basic Auth challenge) */
  def onUnauthorized: Response[IO] = Response[IO](Status.Unauthorized)
    .putHeaders(Header.Raw(ci"WWW-Authenticate", """Basic realm="MCP Server" charset="UTF-8""""))

  /** Acquire shared application resources (DB pools, HTTP clients, Refs, etc.) */
  def mkResources: Resource[IO, R]

  /** Create per-session state from shared resources, the notification sink, and the authenticated user */
  def mkSessionResources(r: R, sink: NotificationSink[IO], user: U): IO[S]

  /** Tool handlers, given shared resources, per-session state, and the authenticated user */
  def tools(r: R, s: S, user: U): List[ToolHandler[IO]] = List.empty

  /** Resource handlers, given shared resources, per-session state, and the authenticated user */
  def resources(r: R, s: S, user: U): List[ResourceHandler[IO]] = List.empty

  /** Resource template handlers, given shared resources, per-session state, and the authenticated user */
  def resourceTemplates(r: R, s: S, user: U): List[ResourceTemplateHandler[IO]] = List.empty

  /** Prompt handlers, given shared resources, per-session state, and the authenticated user */
  def prompts(r: R, s: S, user: U): List[PromptHandler[IO]] = List.empty

  /**
   * Helper method to build a tool handler.
   */
  def tool: ToolBuilder.Empty[IO] = Tool.builder[IO]

  final def run: IO[Unit] =
    mkResources.flatMap { r =>
      val serverFactory: (U, NotificationSink[IO]) => IO[Server[IO]] = { (user, sink) =>
        mkSessionResources(r, sink, user).flatMap { s =>
          ServerBuilder[IO](serverName, serverVersion)
            .withTools(tools(r, s, user)*)
            .withResources(resources(r, s, user)*)
            .withResourceTemplates(resourceTemplates(r, s, user)*)
            .withPrompts(prompts(r, s, user)*)
            .enableResourceSubscriptions
            .enableLogging
            .build
        }
      }

      HttpMcpRouting.serveAuthenticated[U](
        authenticate, serverFactory, onUnauthorized,
        host, port, explorerEnabled, rootRedirectToExplorer, transformServer
      )
    }.useForever