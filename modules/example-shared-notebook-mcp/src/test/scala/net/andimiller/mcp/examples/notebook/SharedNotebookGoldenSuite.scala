package net.andimiller.mcp.examples.notebook

import cats.effect.IO

import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.golden.McpGoldenSuite
import net.andimiller.mcp.http4s.McpHttp

import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

private given LoggerFactory[IO] = Slf4jFactory.create[IO]

/** Golden snapshot of the catalogue as seen by an admin user (alice). Should include the admin-only tools
  * `list_all_notes` and `delete_any_note` alongside the common tools.
  */
class SharedNotebookAdminGoldenSuite extends McpGoldenSuite:

  override def goldenFileName = "shared-notebook-admin.json"

  def server: IO[Server[IO]] =
    Notebook.create[IO].flatMap { notebook =>
      SharedNotebookMcpServer
        .configure(notebook, McpHttp.streaming[IO].name("shared-notebook-mcp").version("1.0.0"))
        .buildServerAs(UserContext("alice", isAdmin = true))
    }

/** Golden snapshot of the catalogue as seen by a non-admin user (bob). The admin-only tools must be filtered out. */
class SharedNotebookGuestGoldenSuite extends McpGoldenSuite:

  override def goldenFileName = "shared-notebook-guest.json"

  def server: IO[Server[IO]] =
    Notebook.create[IO].flatMap { notebook =>
      SharedNotebookMcpServer
        .configure(notebook, McpHttp.streaming[IO].name("shared-notebook-mcp").version("1.0.0"))
        .buildServerAs(UserContext("bob", isAdmin = false))
    }
