package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*

import net.andimiller.mcp.core.protocol.*

import io.circe.Json
import io.circe.JsonObject

class Prompt[F[_], Ctx](
    val name: String,
    val description: Option[String],
    val arguments: List[PromptArgument],
    val handler: (Ctx, Map[String, Json]) => F[GetPromptResponse],
    val title: Option[String] = None,
    val icons: List[Icon] = Nil,
    val meta: Option[JsonObject] = None
):

  def provide(ctx: Ctx): Prompt.Resolved[F] =
    val self = this
    new Prompt.Resolved[F]:
      val name                                                    = self.name
      val description                                             = self.description
      val arguments                                               = self.arguments
      override val title                                          = self.title
      override val icons                                          = self.icons
      override val meta                                           = self.meta
      def get(arguments: Map[String, Json]): F[GetPromptResponse] = self.handler(ctx, arguments)

object Prompt:

  trait Resolved[F[_]]:

    def name: String

    def description: Option[String]

    def arguments: List[PromptArgument]

    def title: Option[String] = None

    def icons: List[Icon] = Nil

    def meta: Option[JsonObject] = None

    def get(arguments: Map[String, Json]): F[GetPromptResponse]

  extension [F[_]](prompt: Prompt[F, Unit]) def resolve: Resolved[F] = prompt.provide(())

  def builder: PromptBuilder.Empty[Unit] =
    new PromptBuilder.Empty[Unit]

  def contextual[Ctx]: PromptBuilder.Empty[Ctx] =
    new PromptBuilder.Empty[Ctx]

  /** Create a simple prompt with static messages. */
  def static[F[_]: Async](
      promptName: String,
      messages: List[PromptMessage],
      promptDescription: Option[String] = None,
      promptArguments: List[PromptArgument] = Nil
  ): Prompt[F, Unit] =
    new Prompt[F, Unit](
      name = promptName,
      description = promptDescription,
      arguments = promptArguments,
      handler = (_, _) => Async[F].pure(GetPromptResponse(promptDescription, messages))
    )

  /** Create a dynamic prompt that generates messages based on arguments. */
  def dynamic[F[_]: Async](
      promptName: String,
      generator: Map[String, Json] => F[List[PromptMessage]],
      promptDescription: Option[String] = None,
      promptArguments: List[PromptArgument] = Nil
  ): Prompt[F, Unit] =
    new Prompt[F, Unit](
      name = promptName,
      description = promptDescription,
      arguments = promptArguments,
      handler = (_, args) => generator(args).map(messages => GetPromptResponse(promptDescription, messages))
    )

object PromptBuilder:

  final class Empty[Ctx]:

    def name(n: String): Builder[Ctx] =
      new Builder[Ctx](n, None, Nil)

  final class Builder[Ctx] private[PromptBuilder] (
      private[PromptBuilder] val promptName: String,
      private[PromptBuilder] val promptDescription: Option[String],
      private[PromptBuilder] val promptArguments: List[PromptArgument],
      private[PromptBuilder] val title: Option[String] = None,
      private[PromptBuilder] val icons: List[Icon] = Nil,
      private[PromptBuilder] val meta: Option[JsonObject] = None
  ):

    private def copy(
        promptName: String = this.promptName,
        promptDescription: Option[String] = this.promptDescription,
        promptArguments: List[PromptArgument] = this.promptArguments,
        title: Option[String] = this.title,
        icons: List[Icon] = this.icons,
        meta: Option[JsonObject] = this.meta
    ): Builder[Ctx] =
      new Builder[Ctx](promptName, promptDescription, promptArguments, title, icons, meta)

    def description(d: String): Builder[Ctx] =
      copy(promptDescription = Some(d))

    def argument(
        name: String,
        description: Option[String] = None,
        required: Boolean = false,
        title: Option[String] = None
    ): Builder[Ctx] =
      copy(promptArguments = promptArguments :+ PromptArgument(name, description, required, title))

    def title(t: String): Builder[Ctx] = copy(title = Some(t))

    def icon(i: Icon): Builder[Ctx] = copy(icons = icons :+ i)

    def icons(xs: List[Icon]): Builder[Ctx] = copy(icons = xs)

    def meta(m: JsonObject): Builder[Ctx] = copy(meta = Some(m))

    def meta(key: String, value: Json): Builder[Ctx] =
      val base = meta.getOrElse(JsonObject.empty)
      copy(meta = Some(base.add(key, value)))

    def metadata(m: Metadata): Builder[Ctx] =
      copy(title = m.title, icons = m.icons, meta = m.meta)

  extension [Ctx](b: Builder[Ctx])

    def generate[F[_]: Async](generator: (Ctx, Map[String, Json]) => F[List[PromptMessage]]): Prompt[F, Ctx] =
      val desc = b.promptDescription
      new Prompt[F, Ctx](
        name = b.promptName,
        description = b.promptDescription,
        arguments = b.promptArguments,
        handler = (ctx, args) => generator(ctx, args).map(messages => GetPromptResponse(desc, messages)),
        title = b.title,
        icons = b.icons,
        meta = b.meta
      )

  extension (b: Builder[Unit])

    def messages[F[_]: Async](msgs: List[PromptMessage]): Prompt[F, Unit] =
      new Prompt[F, Unit](
        name = b.promptName,
        description = b.promptDescription,
        arguments = b.promptArguments,
        handler = (_, _) => Async[F].pure(GetPromptResponse(b.promptDescription, msgs)),
        title = b.title,
        icons = b.icons,
        meta = b.meta
      )

    def generate[F[_]: Async](generator: Map[String, Json] => F[List[PromptMessage]]): Prompt[F, Unit] =
      val desc = b.promptDescription
      new Prompt[F, Unit](
        name = b.promptName,
        description = b.promptDescription,
        arguments = b.promptArguments,
        handler = (_, args) => generator(args).map(messages => GetPromptResponse(desc, messages)),
        title = b.title,
        icons = b.icons,
        meta = b.meta
      )
