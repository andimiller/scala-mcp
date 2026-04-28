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
      val uriTemplate                                   = self.uriTemplate
      val name                                          = self.name
      val description                                   = self.description
      val mimeType                                      = self.mimeType
      def read(uri: String): Option[F[ResourceContent]] = self.reader(ctx, uri)

object ResourceTemplate:

  trait Resolved[F[_]]:

    def uriTemplate: String

    def name: String

    def description: Option[String]

    def mimeType: Option[String]

    def read(uri: String): Option[F[ResourceContent]]

  extension [F[_]](rt: ResourceTemplate[F, Unit]) def resolve: Resolved[F] = rt.provide(())

  def builder: ResourceTemplateBuilder.Empty[Unit] =
    new ResourceTemplateBuilder.Empty[Unit]

  def contextual[Ctx]: ResourceTemplateBuilder.Empty[Ctx] =
    new ResourceTemplateBuilder.Empty[Ctx]

object ResourceTemplateBuilder:

  final class Empty[Ctx]:

    def uriTemplate(t: String): Builder[Ctx] =
      new Builder[Ctx](t, t, None, None)

    def path[A](p: UriPath[A]): PathBuilder[Ctx, A] =
      new PathBuilder[Ctx, A](p, p.template, None, None)

  final class Builder[Ctx] private[ResourceTemplateBuilder] (
      private[ResourceTemplateBuilder] val templateUri: String,
      private[ResourceTemplateBuilder] val templateName: String,
      private[ResourceTemplateBuilder] val templateDescription: Option[String],
      private[ResourceTemplateBuilder] val templateMimeType: Option[String]
  ):

    private def copy(
        templateUri: String = this.templateUri,
        templateName: String = this.templateName,
        templateDescription: Option[String] = this.templateDescription,
        templateMimeType: Option[String] = this.templateMimeType
    ): Builder[Ctx] =
      new Builder[Ctx](templateUri, templateName, templateDescription, templateMimeType)

    def name(n: String): Builder[Ctx] =
      copy(templateName = n)

    def description(d: String): Builder[Ctx] =
      copy(templateDescription = Some(d))

    def mimeType(m: String): Builder[Ctx] =
      copy(templateMimeType = Some(m))

  final class PathBuilder[Ctx, A] private[ResourceTemplateBuilder] (
      private[ResourceTemplateBuilder] val uriPath: UriPath[A],
      private[ResourceTemplateBuilder] val templateName: String,
      private[ResourceTemplateBuilder] val templateDescription: Option[String],
      private[ResourceTemplateBuilder] val templateMimeType: Option[String]
  ):

    private def copy(
        templateName: String = this.templateName,
        templateDescription: Option[String] = this.templateDescription,
        templateMimeType: Option[String] = this.templateMimeType
    ): PathBuilder[Ctx, A] =
      new PathBuilder[Ctx, A](uriPath, templateName, templateDescription, templateMimeType)

    def name(n: String): PathBuilder[Ctx, A] =
      copy(templateName = n)

    def description(d: String): PathBuilder[Ctx, A] =
      copy(templateDescription = Some(d))

    def mimeType(m: String): PathBuilder[Ctx, A] =
      copy(templateMimeType = Some(m))

  extension [Ctx](b: Builder[Ctx])

    def read[F[_]: Async](reader: (Ctx, String) => Option[F[ResourceContent]]): ResourceTemplate[F, Ctx] =
      new ResourceTemplate[F, Ctx](
        uriTemplate = b.templateUri, name = b.templateName, description = b.templateDescription,
        mimeType = b.templateMimeType, reader = reader
      )

  extension [Ctx, A](b: PathBuilder[Ctx, A])

    def read[F[_]: Async](handler: (Ctx, A) => F[ResourceContent]): ResourceTemplate[F, Ctx] =
      new ResourceTemplate[F, Ctx](
        uriTemplate = b.uriPath.template,
        name = b.templateName,
        description = b.templateDescription,
        mimeType = b.templateMimeType,
        reader = (ctx, uri) => b.uriPath.parse(uri).collect { case (a, "") => handler(ctx, a) }
      )

  extension (b: Builder[Unit])

    def read[F[_]: Async](reader: String => Option[F[ResourceContent]]): ResourceTemplate[F, Unit] =
      new ResourceTemplate[F, Unit](
        uriTemplate = b.templateUri,
        name = b.templateName,
        description = b.templateDescription,
        mimeType = b.templateMimeType,
        reader = (_, uri) => reader(uri)
      )

  extension [A](b: PathBuilder[Unit, A])

    def read[F[_]: Async](handler: A => F[ResourceContent]): ResourceTemplate[F, Unit] =
      new ResourceTemplate[F, Unit](
        uriTemplate = b.uriPath.template,
        name = b.templateName,
        description = b.templateDescription,
        mimeType = b.templateMimeType,
        reader = (_, uri) => b.uriPath.parse(uri).collect { case (a, "") => handler(a) }
      )
