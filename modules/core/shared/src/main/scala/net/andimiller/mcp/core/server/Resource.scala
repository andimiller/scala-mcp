package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import net.andimiller.mcp.core.protocol.*

class McpResource[F[_], Ctx](
    val uri: String,
    val name: String,
    val description: Option[String],
    val mimeType: Option[String],
    val reader: Ctx => F[ResourceContent]
):

  def provide(ctx: Ctx): McpResource.Resolved[F] =
    val self = this
    new McpResource.Resolved[F]:
      val uri                        = self.uri
      val name                       = self.name
      val description                = self.description
      val mimeType                   = self.mimeType
      def read(): F[ResourceContent] = self.reader(ctx)

object McpResource:

  trait Resolved[F[_]]:

    def uri: String

    def name: String

    def description: Option[String]

    def mimeType: Option[String]

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
      private[ResourceBuilder] val resourceMimeType: Option[String]
  ):

    private def copy(
        resourceUri: String = this.resourceUri,
        resourceName: String = this.resourceName,
        resourceDescription: Option[String] = this.resourceDescription,
        resourceMimeType: Option[String] = this.resourceMimeType
    ): Builder[Ctx] =
      new Builder[Ctx](resourceUri, resourceName, resourceDescription, resourceMimeType)

    def name(n: String): Builder[Ctx] =
      copy(resourceName = n)

    def description(d: String): Builder[Ctx] =
      copy(resourceDescription = Some(d))

    def mimeType(m: String): Builder[Ctx] =
      copy(resourceMimeType = Some(m))

  extension [Ctx](b: Builder[Ctx])

    def read[F[_]: Async](reader: Ctx => F[String]): McpResource[F, Ctx] =
      val rUri  = b.resourceUri
      val rMime = b.resourceMimeType
      new McpResource[F, Ctx](
        uri = b.resourceUri,
        name = b.resourceName,
        description = b.resourceDescription,
        mimeType = b.resourceMimeType,
        reader = ctx => reader(ctx).map(content => ResourceContent.text(rUri, content, rMime))
      )

    def readContent[F[_]: Async](reader: Ctx => F[ResourceContent]): McpResource[F, Ctx] =
      new McpResource[F, Ctx](
        uri = b.resourceUri, name = b.resourceName, description = b.resourceDescription, mimeType = b.resourceMimeType,
        reader = reader
      )

  extension (b: Builder[Unit])

    def staticContent[F[_]: Async](content: String): McpResource[F, Unit] =
      McpResource.static[F](b.resourceUri, b.resourceName, content, b.resourceDescription, b.resourceMimeType)

    def read[F[_]: Async](reader: () => F[String]): McpResource[F, Unit] =
      McpResource.dynamic[F](b.resourceUri, b.resourceName, reader, b.resourceDescription, b.resourceMimeType)

    def readContent[F[_]: Async](reader: () => F[ResourceContent]): McpResource[F, Unit] =
      McpResource.fromContent[F](b.resourceUri, b.resourceName, reader, b.resourceDescription, b.resourceMimeType)
