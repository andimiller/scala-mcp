package net.andimiller.mcp.examples.notebook

import cats.Eq
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.protocol.{PromptArgument, PromptMessage, ResourceContent}
import net.andimiller.mcp.core.schema.{JsonSchema, description}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.HttpMcpStatefulAuthenticatedResourceApp
import org.http4s.{Request, Response, Status}
import org.http4s.headers.Authorization
import org.typelevel.ci.CIStringSyntax

case class UserContext(username: String)
object UserContext:
  given Eq[UserContext] = Eq.by(_.username)

object SharedNotebookMcpServer extends HttpMcpStatefulAuthenticatedResourceApp[Notebook[IO], Unit, UserContext]:

  // ── user context & auth ─────────────────────────────────────────────

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

  def authenticate(request: Request[IO]): IO[Option[UserContext]] =
    IO.pure(decodeBasicAuth(request))

  override def onUnauthorized: Response[IO] = Response[IO](Status.Unauthorized)
    .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("WWW-Authenticate"), """Basic realm="Shared Notebook MCP" charset="UTF-8""""))

  // ── config ─────────────────────────────────────────────────────────

  def serverName    = "shared-notebook-mcp"
  def serverVersion = "1.0.0"
  override def port = port"26000"
  override def explorerEnabled       = true
  override def rootRedirectToExplorer = true

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
  case class NoteListResponse(notes: List[NoteSummary]) derives Encoder.AsObject, JsonSchema
  case class NoteSummary(note_id: String, title: String, owner: String) derives Encoder.AsObject, JsonSchema
  case class MessageResponse(message: String) derives Encoder.AsObject, JsonSchema
  case class EmptyRequest() derives JsonSchema, Decoder

  // ── resources ───────────────────────────────────────────────────────

  def mkResources: Resource[IO, Notebook[IO]] =
    Resource.eval(Notebook.create[IO])

  def mkSessionResources(r: Notebook[IO], sink: NotificationSink[IO], user: UserContext): IO[Unit] =
    IO.unit

  // ── tools ─────────────────────────────────────────────────────────

  override def tools(r: Notebook[IO], s: Unit, user: UserContext) = List(
    Tool.buildNamed[IO, WriteNoteRequest, NoteResponse](
      "write_note",
      "Create or update a note. If you already have a note with the same title, it will be updated."
    ) { req =>
      r.writeNote(user.username, req.title, req.content).map { note =>
        NoteResponse(note.id, note.title, note.content, note.owner, note.sharedWith.toList)
      }
    },

    Tool.buildNamed[IO, ReadNoteRequest, NoteResponse](
      "read_note",
      "Read a note by ID. You can read notes you own or that are shared with you."
    ) { req =>
      r.readNote(req.note_id, user.username).flatMap {
        case Some(note) => IO.pure(NoteResponse(note.id, note.title, note.content, note.owner, note.sharedWith.toList))
        case None        => IO.raiseError(new Exception(s"Note '${req.note_id}' not found or not accessible"))
      }
    },

    Tool.buildNamed[IO, ShareNoteRequest, MessageResponse](
      "share_note",
      "Share one of your notes with another user. Only the owner can share."
    ) { req =>
      r.shareNote(req.note_id, user.username, req.username).map {
        case Right(_) => MessageResponse(s"Note '${req.note_id}' shared with ${req.username}")
        case Left(err) => MessageResponse(err)
      }
    },

    Tool.buildNamed[IO, UnshareNoteRequest, MessageResponse](
      "unshare_note",
      "Revoke sharing of one of your notes from a user. Only the owner can unshare."
    ) { req =>
      r.unshareNote(req.note_id, user.username, req.username).map {
        case Right(_) => MessageResponse(s"Note '${req.note_id}' unshared from ${req.username}")
        case Left(err) => MessageResponse(err)
      }
    },

    Tool.buildNamed[IO, EmptyRequest, NoteListResponse](
      "list_my_notes",
      "List all notes you own"
    ) { _ =>
      r.listMyNotes(user.username).map(ns => NoteListResponse(ns.map(n => NoteSummary(n.id, n.title, n.owner))))
    },

    Tool.buildNamed[IO, EmptyRequest, NoteListResponse](
      "list_shared_notes",
      "List notes that other users have shared with you"
    ) { _ =>
      r.listSharedWithMe(user.username).map(ns => NoteListResponse(ns.map(n => NoteSummary(n.id, n.title, n.owner))))
    }
  )

  // ── resource templates ────────────────────────────────────────────

  private val notebookUriPrefix = "notebook://"

  override def resourceTemplates(r: Notebook[IO], s: Unit, user: UserContext) = List(
    new ResourceTemplateHandler[IO]:
      def uriTemplate = "notebook://{username}"
      def name = "User's Notebook"
      override def description = Some("All notes owned by a user (you see your own notes, or only shared notes from others)")
      override def mimeType = Some("text/markdown")
      def read(uri: String): Option[IO[ResourceContent]] =
        Option.when(uri.startsWith(notebookUriPrefix)) {
          val targetUser = uri.stripPrefix(notebookUriPrefix)
          r.notesForUser(targetUser, user.username).map { notes =>
            if notes.isEmpty then ResourceContent.text(uri, s"No notes found for user '$targetUser'.", Some("text/markdown"))
            else
              val content = notes.map { n =>
                s"### ${n.title} (id: ${n.id})\nby ${n.owner}\n"
              }.mkString("# Notes\n\n", "\n\n", "\n")
              ResourceContent.text(uri, content, Some("text/markdown"))
          }
        },

    new ResourceTemplateHandler[IO]:
      def uriTemplate = "notebook://{username}/{note_id}"
      def name = "Note by ID"
      override def description = Some("Read a specific note by ID (you must own it or have it shared with you)")
      override def mimeType = Some("text/markdown")
      def read(uri: String): Option[IO[ResourceContent]] =
        Option.when(uri.startsWith(notebookUriPrefix)) {
          val path = uri.stripPrefix(notebookUriPrefix)
          val noteId = path.substring(path.indexOf('/') + 1)
          r.noteForUser(noteId, user.username).map {
            case Some(note) =>
              ResourceContent.text(uri, s"# ${note.title}\n\n${note.content}\n", Some("text/markdown"))
            case None =>
              ResourceContent.text(uri, s"Note '$noteId' not found or not accessible.", Some("text/markdown"))
          }
        }
  )

  // ── prompts ───────────────────────────────────────────────────────

  override def prompts(r: Notebook[IO], s: Unit, user: UserContext) = List(
    Prompt.dynamic[IO](
      promptName = "summarize_notes",
      promptDescription = Some("Summarize all notes accessible to the current user"),
      generator = { _ =>
        for
          owned    <- r.listMyNotes(user.username)
          shared   <- r.listSharedWithMe(user.username)
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
    ),

    Prompt.dynamic[IO](
      promptName = "collaborate_with",
      promptDescription = Some("Plan a collaboration with another user by sharing relevant notes"),
      promptArguments = List(
        PromptArgument("username", Some("The user you want to collaborate with"), required = true),
        PromptArgument("topic", Some("What you want to collaborate on"), required = false)
      ),
      generator = { args =>
        val target  = args.get("username").flatMap(_.asString).getOrElse("unknown")
        val topic   = args.get("topic").flatMap(_.asString).getOrElse("general collaboration")
        r.listMyNotes(user.username).map { owned =>
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
  )