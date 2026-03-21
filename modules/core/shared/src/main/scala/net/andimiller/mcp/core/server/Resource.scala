package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import net.andimiller.mcp.core.protocol.*

/**
 * Resource builder and helper functions for creating resource handlers.
 */
object Resource:

  /**
   * Create a static resource with fixed content.
   */
  def static[F[_]: Async](
    resourceUri: String,
    resourceName: String,
    content: String,
    resourceDescription: Option[String] = None,
    resourceMimeType: Option[String] = None
  ): ResourceHandler[F] =
    new ResourceHandler[F]:
      def uri: String = resourceUri
      def name: String = resourceName
      override def description: Option[String] = resourceDescription
      override def mimeType: Option[String] = resourceMimeType
      def read(): F[ResourceContent] =
        Async[F].pure(ResourceContent.text(resourceUri, content, resourceMimeType))

  /**
   * Create a dynamic resource that computes content on each read.
   */
  def dynamic[F[_]: Async](
    resourceUri: String,
    resourceName: String,
    reader: () => F[String],
    resourceDescription: Option[String] = None,
    resourceMimeType: Option[String] = None
  ): ResourceHandler[F] =
    new ResourceHandler[F]:
      def uri: String = resourceUri
      def name: String = resourceName
      override def description: Option[String] = resourceDescription
      override def mimeType: Option[String] = resourceMimeType
      def read(): F[ResourceContent] =
        reader().map(content => ResourceContent.text(resourceUri, content, resourceMimeType))

  /**
   * Create a resource that returns ResourceContent directly.
   */
  def fromContent[F[_]: Async](
    resourceUri: String,
    resourceName: String,
    reader: () => F[ResourceContent],
    resourceDescription: Option[String] = None,
    resourceMimeType: Option[String] = None
  ): ResourceHandler[F] =
    new ResourceHandler[F]:
      def uri: String = resourceUri
      def name: String = resourceName
      override def description: Option[String] = resourceDescription
      override def mimeType: Option[String] = resourceMimeType
      def read(): F[ResourceContent] = reader()

  /**
   * Fluent builder for resources.
   */
  def builder[F[_]: Async]: ResourceBuilder[F] = new ResourceBuilder[F]

/**
 * Fluent builder for constructing resource handlers.
 */
class ResourceBuilder[F[_]: Async]:
  private var resourceUri: Option[String] = None
  private var resourceName: Option[String] = None
  private var resourceDescription: Option[String] = None
  private var resourceMimeType: Option[String] = None

  def uri(u: String): this.type =
    resourceUri = Some(u)
    this

  def name(n: String): this.type =
    resourceName = Some(n)
    this

  def description(d: String): this.type =
    resourceDescription = Some(d)
    this

  def mimeType(m: String): this.type =
    resourceMimeType = Some(m)
    this

  def staticContent(content: String): ResourceHandler[F] =
    require(resourceUri.isDefined, "Resource URI is required")
    require(resourceName.isDefined, "Resource name is required")

    Resource.static[F](
      resourceUri.get,
      resourceName.get,
      content,
      resourceDescription,
      resourceMimeType
    )

  def dynamicContent(reader: () => F[String]): ResourceHandler[F] =
    require(resourceUri.isDefined, "Resource URI is required")
    require(resourceName.isDefined, "Resource name is required")

    Resource.dynamic[F](
      resourceUri.get,
      resourceName.get,
      reader,
      resourceDescription,
      resourceMimeType
    )

  def content(reader: () => F[ResourceContent]): ResourceHandler[F] =
    require(resourceUri.isDefined, "Resource URI is required")
    require(resourceName.isDefined, "Resource name is required")

    Resource.fromContent[F](
      resourceUri.get,
      resourceName.get,
      reader,
      resourceDescription,
      resourceMimeType
    )
