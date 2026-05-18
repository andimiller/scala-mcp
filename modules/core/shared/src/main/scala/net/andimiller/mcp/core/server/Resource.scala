package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*

import net.andimiller.mcp.core.protocol.*

import io.circe.Json
import io.circe.JsonObject

class McpResource[F[_], Ctx](
    val uri: String,
    val name: String,
    val description: Option[String],
    val mimeType: Option[String],
    val reader: Ctx => F[ResourceContent],
    val title: Option[String] = None,
    val icons: List[Icon] = Nil,
    val annotations: Option[Annotations] = None,
    val size: Option[Long] = None,
    val meta: Option[JsonObject] = None
):

  def provide(ctx: Ctx): McpResource.Resolved[F] =
    val self = this
    new McpResource.Resolved[F]:
      val uri                        = self.uri
      val name                       = self.name
      val description                = self.description
      val mimeType                   = self.mimeType
      override val title             = self.title
      override val icons             = self.icons
      override val annotations       = self.annotations
      override val size              = self.size
      override val meta              = self.meta
      def read(): F[ResourceContent] = self.reader(ctx)

object McpResource:

  trait Resolved[F[_]]:

    def uri: String

    def name: String

    def description: Option[String]

    def mimeType: Option[String]

    def title: Option[String] = None

    def icons: List[Icon] = Nil

    def annotations: Option[Annotations] = None

    def size: Option[Long] = None

    def meta: Option[JsonObject] = None

    def read(): F[ResourceContent]

  extension [F[_]](resource: McpResource[F, Unit]) def resolve: Resolved[F] = resource.provide(())

  def builder: ResourceBuilder.Empty[Unit] =
    new ResourceBuilder.Empty[Unit]

  def contextual[Ctx]: ResourceBuilder.Empty[Ctx] =
    new ResourceBuilder.Empty[Ctx]

  /** Create a static resource with fixed content. */
  def static[F[_]: Async](
      resourceUri: String,
      resourceName: String,
      content: String,
      resourceDescription: Option[String] = None,
      resourceMimeType: Option[String] = None
  ): McpResource[F, Unit] =
    new McpResource[F, Unit](
      uri = resourceUri,
      name = resourceName,
      description = resourceDescription,
      mimeType = resourceMimeType,
      reader = _ => Async[F].pure(ResourceContent.text(resourceUri, content, resourceMimeType))
    )

  /** Create a dynamic resource that computes content on each read. */
  def dynamic[F[_]: Async](
      resourceUri: String,
      resourceName: String,
      reader: () => F[String],
      resourceDescription: Option[String] = None,
      resourceMimeType: Option[String] = None
  ): McpResource[F, Unit] =
    new McpResource[F, Unit](
      uri = resourceUri,
      name = resourceName,
      description = resourceDescription,
      mimeType = resourceMimeType,
      reader = _ => reader().map(content => ResourceContent.text(resourceUri, content, resourceMimeType))
    )

  /** Create a resource that returns ResourceContent directly. */
  def fromContent[F[_]: Async](
      resourceUri: String,
      resourceName: String,
      reader: () => F[ResourceContent],
      resourceDescription: Option[String] = None,
      resourceMimeType: Option[String] = None
  ): McpResource[F, Unit] =
    new McpResource[F, Unit](
      uri = resourceUri,
      name = resourceName,
      description = resourceDescription,
      mimeType = resourceMimeType,
      reader = _ => reader()
    )

object ResourceBuilder:

  final class Empty[Ctx]:

    def uri(u: String): Builder[Ctx] =
      new Builder[Ctx](u, u, None, None)

  final class Builder[Ctx] private[ResourceBuilder] (
      private[ResourceBuilder] val resourceUri: String,
      private[ResourceBuilder] val resourceName: String,
      private[ResourceBuilder] val resourceDescription: Option[String],
      private[ResourceBuilder] val resourceMimeType: Option[String],
      private[ResourceBuilder] val title: Option[String] = None,
      private[ResourceBuilder] val icons: List[Icon] = Nil,
      private[ResourceBuilder] val annotations: Option[Annotations] = None,
      private[ResourceBuilder] val size: Option[Long] = None,
      private[ResourceBuilder] val meta: Option[JsonObject] = None
  ):

    private def copy(
        resourceUri: String = this.resourceUri,
        resourceName: String = this.resourceName,
        resourceDescription: Option[String] = this.resourceDescription,
        resourceMimeType: Option[String] = this.resourceMimeType,
        title: Option[String] = this.title,
        icons: List[Icon] = this.icons,
        annotations: Option[Annotations] = this.annotations,
        size: Option[Long] = this.size,
        meta: Option[JsonObject] = this.meta
    ): Builder[Ctx] =
      new Builder[Ctx](resourceUri, resourceName, resourceDescription, resourceMimeType, title, icons, annotations,
        size, meta)

    def name(n: String): Builder[Ctx] =
      copy(resourceName = n)

    def description(d: String): Builder[Ctx] =
      copy(resourceDescription = Some(d))

    def mimeType(m: String): Builder[Ctx] =
      copy(resourceMimeType = Some(m))

    def title(t: String): Builder[Ctx] = copy(title = Some(t))

    def icon(i: Icon): Builder[Ctx] = copy(icons = icons :+ i)

    def icons(xs: List[Icon]): Builder[Ctx] = copy(icons = xs)

    def annotations(a: Annotations): Builder[Ctx] = copy(annotations = Some(a))

    def size(n: Long): Builder[Ctx] = copy(size = Some(n))

    def meta(m: JsonObject): Builder[Ctx] = copy(meta = Some(m))

    def meta(key: String, value: Json): Builder[Ctx] =
      val base = meta.getOrElse(JsonObject.empty)
      copy(meta = Some(base.add(key, value)))

    def metadata(m: Metadata): Builder[Ctx] =
      copy(title = m.title, icons = m.icons, meta = m.meta)

  extension [Ctx](b: Builder[Ctx])

    def read[F[_]: Async](reader: Ctx => F[String]): McpResource[F, Ctx] =
      val rUri  = b.resourceUri
      val rMime = b.resourceMimeType
      new McpResource[F, Ctx](
        uri = b.resourceUri,
        name = b.resourceName,
        description = b.resourceDescription,
        mimeType = b.resourceMimeType,
        reader = ctx => reader(ctx).map(content => ResourceContent.text(rUri, content, rMime)),
        title = b.title,
        icons = b.icons,
        annotations = b.annotations,
        size = b.size,
        meta = b.meta
      )

    def readContent[F[_]: Async](reader: Ctx => F[ResourceContent]): McpResource[F, Ctx] =
      new McpResource[F, Ctx](
        uri = b.resourceUri, name = b.resourceName, description = b.resourceDescription, mimeType = b.resourceMimeType,
        reader = reader, title = b.title, icons = b.icons, annotations = b.annotations, size = b.size, meta = b.meta
      )

  extension (b: Builder[Unit])

    def staticContent[F[_]: Async](content: String): McpResource[F, Unit] =
      val rUri  = b.resourceUri
      val rMime = b.resourceMimeType
      new McpResource[F, Unit](
        uri = b.resourceUri,
        name = b.resourceName,
        description = b.resourceDescription,
        mimeType = b.resourceMimeType,
        reader = _ => Async[F].pure(ResourceContent.text(rUri, content, rMime)),
        title = b.title,
        icons = b.icons,
        annotations = b.annotations,
        size = b.size,
        meta = b.meta
      )

    def read[F[_]: Async](reader: () => F[String]): McpResource[F, Unit] =
      val rUri  = b.resourceUri
      val rMime = b.resourceMimeType
      new McpResource[F, Unit](
        uri = b.resourceUri,
        name = b.resourceName,
        description = b.resourceDescription,
        mimeType = b.resourceMimeType,
        reader = _ => reader().map(content => ResourceContent.text(rUri, content, rMime)),
        title = b.title,
        icons = b.icons,
        annotations = b.annotations,
        size = b.size,
        meta = b.meta
      )

    def readContent[F[_]: Async](reader: () => F[ResourceContent]): McpResource[F, Unit] =
      new McpResource[F, Unit](
        uri = b.resourceUri,
        name = b.resourceName,
        description = b.resourceDescription,
        mimeType = b.resourceMimeType,
        reader = _ => reader(),
        title = b.title,
        icons = b.icons,
        annotations = b.annotations,
        size = b.size,
        meta = b.meta
      )
