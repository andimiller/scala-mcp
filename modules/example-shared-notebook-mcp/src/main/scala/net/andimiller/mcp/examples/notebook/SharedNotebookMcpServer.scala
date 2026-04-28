package net.andimiller.mcp.examples.notebook

import cats.Eq
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.protocol.{PromptMessage, ResourceContent}
import net.andimiller.mcp.core.schema.{JsonSchema, description}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.McpHttp
import org.http4s.{Header, Request, Response, Status}
import org.http4s.headers.Authorization
import org.typelevel.ci.CIStringSyntax

case class UserContext(username: String)
object UserContext:
  given Eq[UserContext] = Eq.by(_.username)

object SharedNotebookMcpServer extends IOApp.Simple:

  // ── auth ──────────────────────────────────────────────────────────

  private val users = Map(
    "alice"   -> "password123",
    "bob"     -> "password456",
    "charlie" -> "password789"
  )

  private def decodeBasicAuth(request: Request[IO]): Option[UserContext] =
    request.headers.get[Authorization].flatMap {
      case Authorization(org.http4s.BasicCredentials(username, password)) =>
        users.get(username).filter(_ == password).map(_ => UserContext(username))
      case _ => None
    }

  private def authenticate(request: Request[IO]): IO[Option[UserContext]] =
    IO.pure(decodeBasicAuth(request))

  private val onUnauthorized: Response[IO] = Response[IO](Status.Unauthorized)
    .putHeaders(Header.Raw(ci"WWW-Authenticate", """Basic realm="Shared Notebook MCP" charset="UTF-8""""))

  // ── request / response types ─────────────────────────────────────

  case class WriteNoteRequest(
    @description("Title for the note")
    title: String,
    @description("Content of the note (markdown)")
    content: String
  ) derives JsonSchema, Decoder

  case class ReadNoteRequest(
    @description("ID of the note to read")
    note_id: String
  ) derives JsonSchema, Decoder

  case class ShareNoteRequest(
    @description("ID of the note to share")
    note_id: String,
    @description("Username to share the note with")
    username: String
  ) derives JsonSchema, Decoder

  case class UnshareNoteRequest(
    @description("ID of the note to unshare")
    note_id: String,
    @description("Username to revoke sharing from")
    username: String
  ) derives JsonSchema, Decoder

  case class NoteResponse(note_id: String, title: String, content: String, owner: String, shared_with: List[String]) derives Encoder.AsObject, JsonSchema
  case class NoteListResponse(notes: List[NoteListEntry]) derives Encoder.AsObject, JsonSchema
  case class NoteListEntry(note_id: String, title: String, owner: String) derives Encoder.AsObject, JsonSchema
  case class MessageResponse(message: String) derives Encoder.AsObject, JsonSchema
  case class EmptyRequest() derives JsonSchema, Decoder

  // ── server ──────────────────────────────────────────────────────────

  final def run: IO[Unit] =
    Notebook.create[IO].flatMap { notebook =>
      McpHttp.streaming[IO]
        .name("shared-notebook-mcp")
        .version("1.0.0")
        .port(port"26000")
        .withExplorer(redirectToRoot = true)
        .authenticated[UserContext](authenticate, onUnauthorized)
        // ── tools (need the user; notebook is shared across sessions) ──
        .withContextualTool(
          contextualTool[UserContext].name("write_note")
            .description("Create or update a note. If you already have a note with the same title, it will be updated.")
            .in[WriteNoteRequest]
            .out[NoteResponse]
            .run { (user, req) =>
              notebook.writeNote(user.username, req.title, req.content).map { note =>
                NoteResponse(note.id, note.title, note.content, note.owner, note.sharedWith.toList)
              }
            },
        )
        .withContextualTool(
          contextualTool[UserContext].name("read_note")
            .description("Read a note by ID. You can read notes you own or that are shared with you.")
            .in[ReadNoteRequest]
            .out[NoteResponse]
            .run { (user, req) =>
              notebook.readNote(req.note_id, user.username).flatMap {
                case Some(note) => IO.pure(NoteResponse(note.id, note.title, note.content, note.owner, note.sharedWith.toList))
                case None        => IO.raiseError(new Exception(s"Note '${req.note_id}' not found or not accessible"))
              }
            },
        )
        .withContextualTool(
          contextualTool[UserContext].name("share_note")
            .description("Share one of your notes with another user. Only the owner can share.")
            .in[ShareNoteRequest]
            .out[MessageResponse]
            .run { (user, req) =>
              notebook.shareNote(req.note_id, user.username, req.username).map {
                case Right(_) => MessageResponse(s"Note '${req.note_id}' shared with ${req.username}")
                case Left(err) => MessageResponse(err)
              }
            },
        )
        .withContextualTool(
          contextualTool[UserContext].name("unshare_note")
            .description("Revoke sharing of one of your notes from a user. Only the owner can unshare.")
            .in[UnshareNoteRequest]
            .out[MessageResponse]
            .run { (user, req) =>
              notebook.unshareNote(req.note_id, user.username, req.username).map {
                case Right(_) => MessageResponse(s"Note '${req.note_id}' unshared from ${req.username}")
                case Left(err) => MessageResponse(err)
              }
            },
        )
        .withContextualTool(
          contextualTool[UserContext].name("list_my_notes")
            .description("List all notes you own")
            .in[EmptyRequest]
            .out[NoteListResponse]
            .run { (user, _) =>
              notebook.listMyNotes(user.username).map(ns => NoteListResponse(ns.map(n => NoteListEntry(n.id, n.title, n.owner))))
            },
        )
        .withContextualTool(
          contextualTool[UserContext].name("list_shared_notes")
            .description("List notes that other users have shared with you")
            .in[EmptyRequest]
            .out[NoteListResponse]
            .run { (user, _) =>
              notebook.listSharedWithMe(user.username).map(ns => NoteListResponse(ns.map(n => NoteListEntry(n.id, n.title, n.owner))))
            },
        )
        // ── resource templates (need the user; notebook is captured) ──
        .withContextualResourceTemplate(
          contextualResourceTemplate[UserContext]
            .path(path.static("notebook://") *> path.named("username"))
            .name("User's Notebook")
            .description("All notes owned by a user (you see your own notes, or only shared notes from others)")
            .mimeType("text/markdown")
            .read { (user, targetUser) =>
              notebook.notesForUser(targetUser, user.username).map { notes =>
                val uri = s"notebook://$targetUser"
                if notes.isEmpty then ResourceContent.text(uri, s"No notes found for user '$targetUser'.", Some("text/markdown"))
                else
                  val content = notes.map { n =>
                    s"### ${n.title} (id: ${n.id})\nby ${n.owner}\n"
                  }.mkString("# Notes\n\n", "\n\n", "\n")
                  ResourceContent.text(uri, content, Some("text/markdown"))
              }
            }
        )
        .withContextualResourceTemplate(
          contextualResourceTemplate[UserContext]
            .path(
              path.static("notebook://") *>
                (path.named("username"), path.static("/") *> path.named("note_id")).tupled
            )
            .name("Note by ID")
            .description("Read a specific note by ID (you must own it or have it shared with you)")
            .mimeType("text/markdown")
            .read { (user, params) =>
              val (_, noteId) = params
              notebook.noteForUser(noteId, user.username).map {
                case Some(note) =>
                  ResourceContent.text(s"notebook://${params._1}/$noteId", s"# ${note.title}\n\n${note.content}\n", Some("text/markdown"))
                case None =>
                  ResourceContent.text(s"notebook://${params._1}/$noteId", s"Note '$noteId' not found or not accessible.", Some("text/markdown"))
              }
            }
        )
        // ── prompts (need the user; notebook is captured) ─────────────
        .withContextualPrompt(
          contextualPrompt[UserContext]
            .name("summarize_notes")
            .description("Summarize all notes accessible to the current user")
            .generate { (user, _) =>
              for
                owned    <- notebook.listMyNotes(user.username)
                shared   <- notebook.listSharedWithMe(user.username)
                ownedText   = if owned.isEmpty then "No owned notes." else owned.map(n => s"- ${n.title} (${n.id})").mkString("\n")
                sharedText  = if shared.isEmpty then "No shared notes." else shared.map(n => s"- ${n.title} by ${n.owner} (${n.id})").mkString("\n")
              yield List(
                PromptMessage.user(
                  s"""|Please summarize my notebook.
                      |
                      |## My Notes
                      |$ownedText
                      |
                      |## Shared With Me
                      |$sharedText
                      |""".stripMargin
                )
              )
            }
        )
        .withContextualPrompt(
          contextualPrompt[UserContext]
            .name("collaborate_with")
            .description("Plan a collaboration with another user by sharing relevant notes")
            .argument("username", Some("The user you want to collaborate with"), required = true)
            .argument("topic", Some("What you want to collaborate on"), required = false)
            .generate { (user, args) =>
              val target  = args.get("username").flatMap(_.asString).getOrElse("unknown")
              val topic   = args.get("topic").flatMap(_.asString).getOrElse("general collaboration")
              notebook.listMyNotes(user.username).map { owned =>
                val notesList = if owned.isEmpty then "No notes to share yet." else owned.map(n => s"- ${n.title} (${n.id})").mkString("\n")
                List(
                  PromptMessage.user(
                    s"""|I want to collaborate with $target on: $topic
                        |
                        |Here are my notes that might be relevant:
                        |$notesList
                        |
                        |Please help me:
                        |1. Decide which notes to share with $target
                        |2. Draft a message for the collaboration
                        |3. Suggest any new notes I should write
                        |""".stripMargin
                  )
                )
              }
            }
        )
        .enableResourceSubscriptions
        .enableLogging
        .serve
        .useForever
    }
