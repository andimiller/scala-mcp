package net.andimiller.mcp.examples.harness

import fansi.Attrs
import fansi.Bold
import fansi.Color
import fansi.Str

/** Tiny fansi-based palette for the harness REPL. Centralised here so the look-and-feel can be tuned without hunting
  * through every println.
  */
object Theme:

  // Loud assistant text on a fresh line stands out from the [tool …] traces.
  val Assistant: Attrs = Color.LightGreen

  val Tool: Attrs = Color.Yellow

  val Info: Attrs = Color.Cyan

  val Err: Attrs = Color.Red

  val Prompt: Attrs = Bold.On ++ Color.Magenta

  val Dim: Attrs = Color.DarkGray

  def info(msg: String): String = Info(s"[harness] $msg").render

  def err(msg: String): String = Err(s"[harness] $msg").render

  def toolCall(name: String, args: String): String =
    (Tool("[tool] ") ++ Bold.On(Str(name)) ++ Str(" ") ++ Dim(Str(args))).render

  def assistant(text: String): String = Assistant("assistant: ").render + text

  def thinking(text: String): String =
    (Dim(Str("thinking: ")) ++ Dim(Str(text))).render

  // Streaming variants — the label prints once on first token of its kind, fragments print without
  // a label so tokens flow inline. assistantFragment is uncoloured so it renders fast and stays
  // legible against the terminal background; thinkingFragment is dim like the full block form.
  val assistantLabel: String = Assistant("assistant: ").render

  val thinkingLabel: String = Dim(Str("thinking: ")).render

  def thinkingFragment(text: String): String = Dim(Str(text)).render

  val prompt: String = Prompt("> ").render

  // Server→client notifications (logs, list_changed events) — dim and prefixed so they don't get
  // confused with the assistant's voice.
  def notification(msg: String): String = Dim(Str(msg)).render

  // Elicitation per-field prompt label — bold cyan to feel different from the main `> ` prompt
  // since it represents a server-driven question, not a free chat input.
  def elicitPrompt(label: String): String = (Bold.On ++ Color.Cyan).apply(Str(label)).render
