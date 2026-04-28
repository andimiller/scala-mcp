package net.andimiller.mcp.examples.pomodoro

import scala.concurrent.duration.FiniteDuration

/** Timer state machine for the Pomodoro timer. */
enum TimerState:

  case Idle

  case Running(startedAt: Long, duration: FiniteDuration, label: String)

  case Paused(elapsed: FiniteDuration, duration: FiniteDuration, label: String)

  case Completed(label: String, completedAt: Long)

/** A completed pomodoro record for session history. */
case class PomodoroRecord(
    label: String,
    duration: FiniteDuration,
    completedAt: Long
)
