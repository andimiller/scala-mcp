package net.andimiller.mcp.examples.notebook

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.syntax.all.*

case class Note(
    id: String,
    title: String,
    content: String,
    owner: String,
    sharedWith: Set[String] = Set.empty
)

case class NoteSummary(
    id: String,
    title: String,
    owner: String
)

class Notebook[F[_]: Async](
    notes: Ref[F, Map[String, Note]],
    counter: Ref[F, Int]
):

  def writeNote(owner: String, title: String, content: String): F[Note] =
    for
      id       <- counter.getAndUpdate(_ + 1).map(i => s"note-$i")
      existing <- notes.get.map(_.values.find(n => n.owner == owner && n.title == title))
      note     <- existing match
                case Some(existingNote) =>
                  val updated = existingNote.copy(content = content)
                  notes.update(_ + (updated.id -> updated)).as(updated)
                case None =>
                  val note = Note(id, title, content, owner)
                  notes.update(_ + (note.id -> note)).as(note)
    yield note

  def readNote(noteId: String, reader: String): F[Option[Note]] =
    notes.get.map(_.get(noteId).filter(n => n.owner == reader || n.sharedWith.contains(reader)))

  def shareNote(noteId: String, owner: String, target: String): F[Either[String, Unit]] =
    notes.modify { ns =>
      ns.get(noteId) match
        case Some(note) if note.owner == owner =>
          val updated = note.copy(sharedWith = note.sharedWith + target)
          (ns + (noteId -> updated), Right(()))
        case Some(_) =>
          (ns, Left("You can only share your own notes"))
        case None =>
          (ns, Left("Note not found"))
    }

  def unshareNote(noteId: String, owner: String, target: String): F[Either[String, Unit]] =
    notes.modify { ns =>
      ns.get(noteId) match
        case Some(note) if note.owner == owner =>
          val updated = note.copy(sharedWith = note.sharedWith - target)
          (ns + (noteId -> updated), Right(()))
        case Some(_) =>
          (ns, Left("You can only unshare your own notes"))
        case None =>
          (ns, Left("Note not found"))
    }

  def listMyNotes(owner: String): F[List[NoteSummary]] =
    notes.get.map(_.values.filter(_.owner == owner).map(n => NoteSummary(n.id, n.title, n.owner)).toList)

  def listSharedWithMe(username: String): F[List[NoteSummary]] =
    notes.get.map(_.values.filter(_.sharedWith.contains(username)).map(n => NoteSummary(n.id, n.title, n.owner)).toList)

  def notesForUser(target: String, viewer: String): F[List[NoteSummary]] =
    notes.get.map(_.values.filter { n =>
      n.owner == target && (n.owner == viewer || n.sharedWith.contains(viewer))
    }.map(n => NoteSummary(n.id, n.title, n.owner)).toList)

  def noteForUser(noteId: String, viewer: String): F[Option[Note]] =
    notes.get.map(_.get(noteId).filter(n => n.owner == viewer || n.sharedWith.contains(viewer)))

object Notebook:

  def create[F[_]: Async]: F[Notebook[F]] =
    for
      notes   <- Ref.of[F, Map[String, Note]](Map.empty)
      counter <- Ref.of[F, Int](1)
    yield new Notebook[F](notes, counter)
