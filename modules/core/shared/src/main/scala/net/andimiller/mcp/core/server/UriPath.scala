package net.andimiller.mcp.core.server

import cats.Applicative

/** A composable URI matcher that pairs an RFC 6570 template fragment with a parser.
  *
  * @param template
  *   the URI template string fragment (e.g. "pomodoro://timers/{name}")
  * @param parse
  *   given a URI string, returns Some((extracted_value, remaining_string)) or None
  */
case class UriPath[A](template: String, parse: String => Option[(A, String)]):

  def map[B](f: A => B): UriPath[B] =
    UriPath(template, s => parse(s).map((a, rest) => (f(a), rest)))

  def mapOption[B](f: A => Option[B]): UriPath[B] =
    UriPath(template, s => parse(s).flatMap((a, rest) => f(a).map(b => (b, rest))))

object UriPath:

  /** Matches a literal prefix exactly. */
  def static(prefix: String): UriPath[Unit] =
    UriPath(prefix, s => Option.when(s.startsWith(prefix))(((), s.drop(prefix.length))))

  /** Matches one URI segment (up to next `/` or end, non-empty) and names it for the template. */
  def named(name: String): UriPath[String] =
    UriPath(
      s"{$name}",
      s =>
        val idx     = s.indexOf('/')
        val segment = if idx < 0 then s else s.substring(0, idx)
        Option.when(segment.nonEmpty)((segment, s.drop(segment.length)))
    )

  /** Matches all remaining input (possibly empty). */
  def rest: UriPath[String] =
    UriPath("", s => Some((s, "")))

  given Applicative[UriPath] with

    def pure[A](a: A): UriPath[A] =
      UriPath("", s => Some((a, s)))

    def ap[A, B](ff: UriPath[A => B])(fa: UriPath[A]): UriPath[B] =
      UriPath(
        ff.template + fa.template,
        s => ff.parse(s).flatMap((f, rest) => fa.parse(rest).map((a, rest2) => (f(a), rest2)))
      )

trait PathParam[A]:

  def parse(s: String): Option[A]

object PathParam:

  given PathParam[String] = s => Some(s)

  given PathParam[Int] = _.toIntOption

  given PathParam[Long] = _.toLongOption

extension (p: UriPath[String])

  def as[A](using pp: PathParam[A]): UriPath[A] =
    p.mapOption(pp.parse)
