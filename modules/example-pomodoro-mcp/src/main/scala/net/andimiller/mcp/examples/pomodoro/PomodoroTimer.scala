package net.andimiller.mcp.examples.pomodoro

import scala.concurrent.duration.*

import cats.effect.Fiber
import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*

import net.andimiller.mcp.core.server.NotificationSink

import io.circe.Json

/** Core Pomodoro timer logic.
  *
  * Manages a state machine (Idle → Running ⇄ Paused → Completed → Idle) and a background ticker fiber that emits
  * resource-update notifications every second.
  */
class PomodoroTimer(
    state: Ref[IO, TimerState],
    history: Ref[IO, List[PomodoroRecord]],
    fiber: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
    sink: NotificationSink[IO]
):

  /** Start a new timer. Fails if not Idle. */
  def start(durationMinutes: Int, label: String): IO[String] =
    for
      now <- IO.realTime.map(_.toMillis)
      dur  = durationMinutes.minutes
      ok  <- state.modify {
              case TimerState.Idle =>
                (TimerState.Running(now, dur, label), true)
              case other =>
                (other, false)
            }
      _ <- IO.raiseWhen(!ok)(new Exception("Timer is already running or paused. Stop it first."))
      f <- spawnTicker(dur, label).start
      _ <- fiber.set(Some(f))
    yield s"Timer started: $label for $durationMinutes minutes"

  /** Pause a running timer. */
  def pause(): IO[String] =
    for
      now <- IO.realTime.map(_.toMillis)
      res <- state.modify {
               case TimerState.Running(startedAt, dur, label) =>
                 val elapsed = (now - startedAt).millis
                 (TimerState.Paused(elapsed, dur, label), Right(label))
               case other =>
                 (other, Left("Timer is not running"))
             }
      label <- IO.fromEither(res.leftMap(new Exception(_)))
      _     <- cancelFiber
    yield s"Timer paused: $label"

  /** Resume a paused timer. */
  def resume(): IO[String] =
    for
      now <- IO.realTime.map(_.toMillis)
      res <- state.modify {
               case TimerState.Paused(elapsed, dur, label) =>
                 val newStart = now - elapsed.toMillis
                 (TimerState.Running(newStart, dur, label), Right((dur, label)))
               case other =>
                 (other, Left("Timer is not paused"))
             }
      pair        <- IO.fromEither(res.leftMap(new Exception(_)))
      (dur, label) = pair
      f           <- spawnTicker(dur, label).start
      _           <- fiber.set(Some(f))
    yield s"Timer resumed: $label"

  /** Stop/cancel the timer, returning to Idle. */
  def stop(): IO[String] =
    for
      prev <- state.getAndSet(TimerState.Idle)
      _    <- cancelFiber
      msg   = prev match
              case TimerState.Running(_, _, label) => s"Timer stopped: $label"
              case TimerState.Paused(_, _, label)  => s"Timer stopped: $label"
              case _                               => "No timer was running"
    yield msg

  /** Get a human-readable status string. */
  def status: IO[String] =
    for
      now <- IO.realTime.map(_.toMillis)
      s   <- state.get
    yield s match
      case TimerState.Idle =>
        "Idle — no timer running"
      case TimerState.Running(startedAt, dur, label) =>
        val elapsed   = (now - startedAt).millis
        val remaining = (dur - elapsed).max(Duration.Zero)
        val mins      = remaining.toMinutes
        val secs      = remaining.toSeconds % 60
        s"Running: $label — ${mins}m ${secs}s remaining"
      case TimerState.Paused(elapsed, dur, label) =>
        val remaining = (dur - elapsed).max(Duration.Zero)
        val mins      = remaining.toMinutes
        val secs      = remaining.toSeconds % 60
        s"Paused: $label — ${mins}m ${secs}s remaining"
      case TimerState.Completed(label, _) =>
        s"Completed: $label"

  /** Get status for a specific timer by label. Checks current state then history. */
  def statusForLabel(name: String): IO[Option[String]] =
    for
      now <- IO.realTime.map(_.toMillis)
      s   <- state.get
      h   <- history.get
    yield s match
      case TimerState.Running(startedAt, dur, label) if label === name =>
        val elapsed   = (now - startedAt).millis
        val remaining = (dur - elapsed).max(Duration.Zero)
        Some(s"Running: $label — ${remaining.toMinutes}m ${remaining.toSeconds % 60}s remaining")
      case TimerState.Paused(elapsed, dur, label) if label === name =>
        val remaining = (dur - elapsed).max(Duration.Zero)
        Some(s"Paused: $label — ${remaining.toMinutes}m ${remaining.toSeconds % 60}s remaining")
      case TimerState.Completed(label, completedAt) if label === name =>
        Some(s"Completed: $label at ${java.time.Instant.ofEpochMilli(completedAt)}")
      case _ =>
        h.find(_.label === name).map { r =>
          s"Completed: ${r.label} (${r.duration.toMinutes}min) at ${java.time.Instant.ofEpochMilli(r.completedAt)}"
        }

  /** Send a log notification to the connected MCP client. */
  def log(level: String, message: String): IO[Unit] =
    sink.log(level, "pomodoro", Json.fromString(message))

  /** Get formatted session history. */
  def historyText: IO[String] =
    history.get.map { records =>
      if records.isEmpty then "No completed sessions yet."
      else
        records.zipWithIndex.map { (r, i) =>
          s"${i + 1}. ${r.label} (${r.duration.toMinutes}min) — completed at ${java.time.Instant.ofEpochMilli(r.completedAt)}"
        }.mkString("\n")
    }

  // ── internals ──────────────────────────────────────────────────────

  private def cancelFiber: IO[Unit] =
    fiber.getAndSet(None).flatMap {
      case Some(f) => f.cancel
      case None    => IO.unit
    }

  /** Background fiber that ticks once per second, notifying resource updates. */
  private def spawnTicker(duration: FiniteDuration, label: String): IO[Unit] =
    val totalSeconds = duration.toSeconds.toInt
    (1 to totalSeconds).toList.traverse_ { _ =>
      IO.sleep(1.second) *> sink.resourceUpdated("pomodoro://status")
    } *> onComplete(label)

  /** Transition to Completed and record to history. */
  private def onComplete(label: String): IO[Unit] =
    for
      now  <- IO.realTime.map(_.toMillis)
      prev <- state.getAndSet(TimerState.Completed(label, now))
      dur   = prev match
              case TimerState.Running(_, d, _) => d
              case _                           => Duration.Zero
      _ <- history.update(PomodoroRecord(label, dur, now) :: _)
      _ <- fiber.set(None)
      _ <- sink.log("info", "pomodoro", Json.fromString(s"Pomodoro completed: $label"))
      _ <- sink.resourceUpdated("pomodoro://status")
      _ <- sink.resourceUpdated("pomodoro://history")
    yield ()

object PomodoroTimer:

  def create(sink: NotificationSink[IO]): IO[PomodoroTimer] =
    for
      state   <- Ref.of[IO, TimerState](TimerState.Idle)
      history <- Ref.of[IO, List[PomodoroRecord]](Nil)
      fiber   <- Ref.of[IO, Option[Fiber[IO, Throwable, Unit]]](None)
    yield new PomodoroTimer(state, history, fiber, sink)
