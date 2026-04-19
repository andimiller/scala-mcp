package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import net.andimiller.mcp.core.protocol.*

class ResourceTemplate[F[_], Ctx](
    val uriTemplate: String,
    val name: String,
    val description: Option[String],
    val mimeType: Option[String],
    val reader: (Ctx, String) => Option[F[ResourceContent]]
):
  def provide(ctx: Ctx): ResourceTemplate.Resolved[F] =
    val self = this
    new ResourceTemplate.Resolved[F]:
      val uriTemplate = self.uriTemplate
      val name = self.name
      val description = self.description
      val mimeType = self.mimeType
      def read(uri: String): Option[F[ResourceContent]] = self.reader(ctx, uri)

object ResourceTemplate:

  trait Resolved[F[_]]:
    def uriTemplate: String
    def name: String
    def description: Option[String]
    def mimeType: Option[String]
    def read(uri: String): Option[F[ResourceContent]]

  extension [F[_]](rt: ResourceTemplate[F, Unit])
    def resolve: Resolved[F] = rt.provide(())

  def builder[F[_]: Async]: ResourceTemplateBuilder.PlainEmpty[F] =
    new ResourceTemplateBuilder.PlainEmpty[F]

  def contextual[F[_]: Async, Ctx]: ResourceTemplateBuilder.ContextualEmpty[F, Ctx] =
    new ResourceTemplateBuilder.ContextualEmpty[F, Ctx]

object ResourceTemplateBuilder:

  // ── Context-free (plain) builder ──────────────────────────────

  final class PlainEmpty[F[_]: Async]:
    def uriTemplate(t: String): PlainBuilder[F] =
      new PlainBuilder[F](t, t, None, None)
    def path[A](p: UriPath[A]): PlainPathBuilder[F, A] =
      new PlainPathBuilder[F, A](p, p.template, None, None)

  final class PlainBuilder[F[_]: Async] private[ResourceTemplateBuilder] (
      templateUri: String,
      templateName: String,
      templateDescription: Option[String],
      templateMimeType: Option[String]
  ):

    private def copy(
        templateUri: String = this.templateUri,
        templateName: String = this.templateName,
        templateDescription: Option[String] = this.templateDescription,
        templateMimeType: Option[String] = this.templateMimeType
    ): PlainBuilder[F] =
      new PlainBuilder[F](templateUri, templateName, templateDescription, templateMimeType)

    def name(n: String): PlainBuilder[F] =
      copy(templateName = n)

    def description(d: String): PlainBuilder[F] =
      copy(templateDescription = Some(d))

    def mimeType(m: String): PlainBuilder[F] =
      copy(templateMimeType = Some(m))

    def read(reader: String => Option[F[ResourceContent]]): ResourceTemplate[F, Unit] =
      new ResourceTemplate[F, Unit](
        uriTemplate = templateUri,
        name = templateName,
        description = templateDescription,
        mimeType = templateMimeType,
        reader = (_, uri) => reader(uri)
      )

  // ── Contextual builder ──────────────────────────────────────────

  final class ContextualEmpty[F[_]: Async, Ctx]:
    def uriTemplate(t: String): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](t, t, None, None)
    def path[A](p: UriPath[A]): ContextualPathBuilder[F, Ctx, A] =
      new ContextualPathBuilder[F, Ctx, A](p, p.template, None, None)

  final class ContextualBuilder[F[_]: Async, Ctx] private[ResourceTemplateBuilder] (
      templateUri: String,
      templateName: String,
      templateDescription: Option[String],
      templateMimeType: Option[String]
  ):

    private def copy(
        templateUri: String = this.templateUri,
        templateName: String = this.templateName,
        templateDescription: Option[String] = this.templateDescription,
        templateMimeType: Option[String] = this.templateMimeType
    ): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](templateUri, templateName, templateDescription, templateMimeType)

    def name(n: String): ContextualBuilder[F, Ctx] =
      copy(templateName = n)

    def description(d: String): ContextualBuilder[F, Ctx] =
      copy(templateDescription = Some(d))

    def mimeType(m: String): ContextualBuilder[F, Ctx] =
      copy(templateMimeType = Some(m))

    def read(reader: (Ctx, String) => Option[F[ResourceContent]]): ResourceTemplate[F, Ctx] =
      new ResourceTemplate[F, Ctx](
        uriTemplate = templateUri,
        name = templateName,
        description = templateDescription,
        mimeType = templateMimeType,
        reader = reader
      )

  // ── Context-free path builder ───────────────────────────────────

  final class PlainPathBuilder[F[_]: Async, A] private[ResourceTemplateBuilder] (
      uriPath: UriPath[A],
      templateName: String,
      templateDescription: Option[String],
      templateMimeType: Option[String]
  ):

    private def copy(
        templateName: String = this.templateName,
        templateDescription: Option[String] = this.templateDescription,
        templateMimeType: Option[String] = this.templateMimeType
    ): PlainPathBuilder[F, A] =
      new PlainPathBuilder[F, A](uriPath, templateName, templateDescription, templateMimeType)

    def name(n: String): PlainPathBuilder[F, A] =
      copy(templateName = n)

    def description(d: String): PlainPathBuilder[F, A] =
      copy(templateDescription = Some(d))

    def mimeType(m: String): PlainPathBuilder[F, A] =
      copy(templateMimeType = Some(m))

    def read(handler: A => F[ResourceContent]): ResourceTemplate[F, Unit] =
      new ResourceTemplate[F, Unit](
        uriTemplate = uriPath.template,
        name = templateName,
        description = templateDescription,
        mimeType = templateMimeType,
        reader = (_, uri) => uriPath.parse(uri).collect { case (a, "") => handler(a) }
      )

  // ── Contextual path builder ─────────────────────────────────────

  final class ContextualPathBuilder[F[_]: Async, Ctx, A] private[ResourceTemplateBuilder] (
      uriPath: UriPath[A],
      templateName: String,
      templateDescription: Option[String],
      templateMimeType: Option[String]
  ):

    private def copy(
        templateName: String = this.templateName,
        templateDescription: Option[String] = this.templateDescription,
        templateMimeType: Option[String] = this.templateMimeType
    ): ContextualPathBuilder[F, Ctx, A] =
      new ContextualPathBuilder[F, Ctx, A](uriPath, templateName, templateDescription, templateMimeType)

    def name(n: String): ContextualPathBuilder[F, Ctx, A] =
      copy(templateName = n)

    def description(d: String): ContextualPathBuilder[F, Ctx, A] =
      copy(templateDescription = Some(d))

    def mimeType(m: String): ContextualPathBuilder[F, Ctx, A] =
      copy(templateMimeType = Some(m))

    def read(handler: (Ctx, A) => F[ResourceContent]): ResourceTemplate[F, Ctx] =
      new ResourceTemplate[F, Ctx](
        uriTemplate = uriPath.template,
        name = templateName,
        description = templateDescription,
        mimeType = templateMimeType,
        reader = (ctx, uri) => uriPath.parse(uri).collect { case (a, "") => handler(ctx, a) }
      )
