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
      val uri = self.uri
      val name = self.name
      val description = self.description
      val mimeType = self.mimeType
      def read(): F[ResourceContent] = self.reader(ctx)

object McpResource:

  trait Resolved[F[_]]:
    def uri: String
    def name: String
    def description: Option[String]
    def mimeType: Option[String]
    def read(): F[ResourceContent]

  extension [F[_]](resource: McpResource[F, Unit])
    def resolve: Resolved[F] = resource.provide(())

  def builder[F[_]: Async]: ResourceBuilder.PlainEmpty[F] =
    new ResourceBuilder.PlainEmpty[F]

  def contextual[F[_]: Async, Ctx]: ResourceBuilder.ContextualEmpty[F, Ctx] =
    new ResourceBuilder.ContextualEmpty[F, Ctx]

  /**
   * Create a static resource with fixed content.
   */
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

  /**
   * Create a dynamic resource that computes content on each read.
   */
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

  /**
   * Create a resource that returns ResourceContent directly.
   */
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

  // ── Context-free (plain) builder ──────────────────────────────

  final class PlainEmpty[F[_]: Async]:
    def uri(u: String): PlainBuilder[F] =
      new PlainBuilder[F](u, u, None, None)

  final class PlainBuilder[F[_]: Async] private[ResourceBuilder] (
      resourceUri: String,
      resourceName: String,
      resourceDescription: Option[String],
      resourceMimeType: Option[String]
  ):

    private def copy(
        resourceUri: String = this.resourceUri,
        resourceName: String = this.resourceName,
        resourceDescription: Option[String] = this.resourceDescription,
        resourceMimeType: Option[String] = this.resourceMimeType
    ): PlainBuilder[F] =
      new PlainBuilder[F](resourceUri, resourceName, resourceDescription, resourceMimeType)

    def name(n: String): PlainBuilder[F] =
      copy(resourceName = n)

    def description(d: String): PlainBuilder[F] =
      copy(resourceDescription = Some(d))

    def mimeType(m: String): PlainBuilder[F] =
      copy(resourceMimeType = Some(m))

    def staticContent(content: String): McpResource[F, Unit] =
      McpResource.static[F](resourceUri, resourceName, content, resourceDescription, resourceMimeType)

    def read(reader: () => F[String]): McpResource[F, Unit] =
      McpResource.dynamic[F](resourceUri, resourceName, reader, resourceDescription, resourceMimeType)

    def readContent(reader: () => F[ResourceContent]): McpResource[F, Unit] =
      McpResource.fromContent[F](resourceUri, resourceName, reader, resourceDescription, resourceMimeType)

  // ── Contextual builder ──────────────────────────────────────────

  final class ContextualEmpty[F[_]: Async, Ctx]:
    def uri(u: String): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](u, u, None, None)

  final class ContextualBuilder[F[_]: Async, Ctx] private[ResourceBuilder] (
      resourceUri: String,
      resourceName: String,
      resourceDescription: Option[String],
      resourceMimeType: Option[String]
  ):

    private def copy(
        resourceUri: String = this.resourceUri,
        resourceName: String = this.resourceName,
        resourceDescription: Option[String] = this.resourceDescription,
        resourceMimeType: Option[String] = this.resourceMimeType
    ): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](resourceUri, resourceName, resourceDescription, resourceMimeType)

    def name(n: String): ContextualBuilder[F, Ctx] =
      copy(resourceName = n)

    def description(d: String): ContextualBuilder[F, Ctx] =
      copy(resourceDescription = Some(d))

    def mimeType(m: String): ContextualBuilder[F, Ctx] =
      copy(resourceMimeType = Some(m))

    def read(reader: Ctx => F[String]): McpResource[F, Ctx] =
      val rUri = resourceUri
      val rMime = resourceMimeType
      new McpResource[F, Ctx](
        uri = resourceUri,
        name = resourceName,
        description = resourceDescription,
        mimeType = resourceMimeType,
        reader = ctx => reader(ctx).map(content => ResourceContent.text(rUri, content, rMime))
      )

    def readContent(reader: Ctx => F[ResourceContent]): McpResource[F, Ctx] =
      new McpResource[F, Ctx](
        uri = resourceUri,
        name = resourceName,
        description = resourceDescription,
        mimeType = resourceMimeType,
        reader = reader
      )
