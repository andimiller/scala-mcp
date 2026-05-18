package net.andimiller.mcp.core.server

import cats.effect.kernel.Async

import net.andimiller.mcp.core.protocol.*

import io.circe.Json
import io.circe.JsonObject

class ResourceTemplate[F[_], Ctx](
    val uriTemplate: String,
    val name: String,
    val description: Option[String],
    val mimeType: Option[String],
    val reader: (Ctx, String) => Option[F[ResourceContent]],
    val title: Option[String] = None,
    val icons: List[Icon] = Nil,
    val annotations: Option[Annotations] = None,
    val meta: Option[JsonObject] = None
):

  def provide(ctx: Ctx): ResourceTemplate.Resolved[F] =
    val self = this
    new ResourceTemplate.Resolved[F]:
      val uriTemplate                                   = self.uriTemplate
      val name                                          = self.name
      val description                                   = self.description
      val mimeType                                      = self.mimeType
      override val title                                = self.title
      override val icons                                = self.icons
      override val annotations                          = self.annotations
      override val meta                                 = self.meta
      def read(uri: String): Option[F[ResourceContent]] = self.reader(ctx, uri)

object ResourceTemplate:

  trait Resolved[F[_]]:

    def uriTemplate: String

    def name: String

    def description: Option[String]

    def mimeType: Option[String]

    def title: Option[String] = None

    def icons: List[Icon] = Nil

    def annotations: Option[Annotations] = None

    def meta: Option[JsonObject] = None

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
      private[ResourceTemplateBuilder] val templateMimeType: Option[String],
      private[ResourceTemplateBuilder] val title: Option[String] = None,
      private[ResourceTemplateBuilder] val icons: List[Icon] = Nil,
      private[ResourceTemplateBuilder] val annotations: Option[Annotations] = None,
      private[ResourceTemplateBuilder] val meta: Option[JsonObject] = None
  ):

    private def copy(
        templateUri: String = this.templateUri,
        templateName: String = this.templateName,
        templateDescription: Option[String] = this.templateDescription,
        templateMimeType: Option[String] = this.templateMimeType,
        title: Option[String] = this.title,
        icons: List[Icon] = this.icons,
        annotations: Option[Annotations] = this.annotations,
        meta: Option[JsonObject] = this.meta
    ): Builder[Ctx] =
      new Builder[Ctx](templateUri, templateName, templateDescription, templateMimeType, title, icons, annotations,
        meta)

    def name(n: String): Builder[Ctx] =
      copy(templateName = n)

    def description(d: String): Builder[Ctx] =
      copy(templateDescription = Some(d))

    def mimeType(m: String): Builder[Ctx] =
      copy(templateMimeType = Some(m))

    def title(t: String): Builder[Ctx] = copy(title = Some(t))

    def icon(i: Icon): Builder[Ctx] = copy(icons = icons :+ i)

    def icons(xs: List[Icon]): Builder[Ctx] = copy(icons = xs)

    def annotations(a: Annotations): Builder[Ctx] = copy(annotations = Some(a))

    def meta(m: JsonObject): Builder[Ctx] = copy(meta = Some(m))

    def meta(key: String, value: Json): Builder[Ctx] =
      val base = meta.getOrElse(JsonObject.empty)
      copy(meta = Some(base.add(key, value)))

    def metadata(m: Metadata): Builder[Ctx] =
      copy(title = m.title, icons = m.icons, meta = m.meta)

  final class PathBuilder[Ctx, A] private[ResourceTemplateBuilder] (
      private[ResourceTemplateBuilder] val uriPath: UriPath[A],
      private[ResourceTemplateBuilder] val templateName: String,
      private[ResourceTemplateBuilder] val templateDescription: Option[String],
      private[ResourceTemplateBuilder] val templateMimeType: Option[String],
      private[ResourceTemplateBuilder] val title: Option[String] = None,
      private[ResourceTemplateBuilder] val icons: List[Icon] = Nil,
      private[ResourceTemplateBuilder] val annotations: Option[Annotations] = None,
      private[ResourceTemplateBuilder] val meta: Option[JsonObject] = None
  ):

    private def copy(
        templateName: String = this.templateName,
        templateDescription: Option[String] = this.templateDescription,
        templateMimeType: Option[String] = this.templateMimeType,
        title: Option[String] = this.title,
        icons: List[Icon] = this.icons,
        annotations: Option[Annotations] = this.annotations,
        meta: Option[JsonObject] = this.meta
    ): PathBuilder[Ctx, A] =
      new PathBuilder[Ctx, A](uriPath, templateName, templateDescription, templateMimeType, title, icons, annotations,
        meta)

    def name(n: String): PathBuilder[Ctx, A] =
      copy(templateName = n)

    def description(d: String): PathBuilder[Ctx, A] =
      copy(templateDescription = Some(d))

    def mimeType(m: String): PathBuilder[Ctx, A] =
      copy(templateMimeType = Some(m))

    def title(t: String): PathBuilder[Ctx, A] = copy(title = Some(t))

    def icon(i: Icon): PathBuilder[Ctx, A] = copy(icons = icons :+ i)

    def icons(xs: List[Icon]): PathBuilder[Ctx, A] = copy(icons = xs)

    def annotations(a: Annotations): PathBuilder[Ctx, A] = copy(annotations = Some(a))

    def meta(m: JsonObject): PathBuilder[Ctx, A] = copy(meta = Some(m))

    def meta(key: String, value: Json): PathBuilder[Ctx, A] =
      val base = meta.getOrElse(JsonObject.empty)
      copy(meta = Some(base.add(key, value)))

    def metadata(m: Metadata): PathBuilder[Ctx, A] =
      copy(title = m.title, icons = m.icons, meta = m.meta)

  extension [Ctx](b: Builder[Ctx])

    def read[F[_]: Async](reader: (Ctx, String) => Option[F[ResourceContent]]): ResourceTemplate[F, Ctx] =
      new ResourceTemplate[F, Ctx](
        uriTemplate = b.templateUri, name = b.templateName, description = b.templateDescription,
        mimeType = b.templateMimeType, reader = reader, title = b.title, icons = b.icons, annotations = b.annotations,
        meta = b.meta
      )

  extension [Ctx, A](b: PathBuilder[Ctx, A])

    def read[F[_]: Async](handler: (Ctx, A) => F[ResourceContent]): ResourceTemplate[F, Ctx] =
      new ResourceTemplate[F, Ctx](
        uriTemplate = b.uriPath.template,
        name = b.templateName,
        description = b.templateDescription,
        mimeType = b.templateMimeType,
        reader = (ctx, uri) => b.uriPath.parse(uri).collect { case (a, "") => handler(ctx, a) },
        title = b.title,
        icons = b.icons,
        annotations = b.annotations,
        meta = b.meta
      )

  extension (b: Builder[Unit])

    def read[F[_]: Async](reader: String => Option[F[ResourceContent]]): ResourceTemplate[F, Unit] =
      new ResourceTemplate[F, Unit](
        uriTemplate = b.templateUri,
        name = b.templateName,
        description = b.templateDescription,
        mimeType = b.templateMimeType,
        reader = (_, uri) => reader(uri),
        title = b.title,
        icons = b.icons,
        annotations = b.annotations,
        meta = b.meta
      )

  extension [A](b: PathBuilder[Unit, A])

    def read[F[_]: Async](handler: A => F[ResourceContent]): ResourceTemplate[F, Unit] =
      new ResourceTemplate[F, Unit](
        uriTemplate = b.uriPath.template,
        name = b.templateName,
        description = b.templateDescription,
        mimeType = b.templateMimeType,
        reader = (_, uri) => b.uriPath.parse(uri).collect { case (a, "") => handler(a) },
        title = b.title,
        icons = b.icons,
        annotations = b.annotations,
        meta = b.meta
      )
