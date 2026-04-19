package net.andimiller.mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import io.circe.Json
import net.andimiller.mcp.core.protocol.*

class Prompt[F[_], Ctx](
    val name: String,
    val description: Option[String],
    val arguments: List[PromptArgument],
    val handler: (Ctx, Map[String, Json]) => F[GetPromptResponse]
):
  def provide(ctx: Ctx): Prompt.Resolved[F] =
    val self = this
    new Prompt.Resolved[F]:
      val name = self.name
      val description = self.description
      val arguments = self.arguments
      def get(arguments: Map[String, Json]): F[GetPromptResponse] = self.handler(ctx, arguments)

object Prompt:

  trait Resolved[F[_]]:
    def name: String
    def description: Option[String]
    def arguments: List[PromptArgument]
    def get(arguments: Map[String, Json]): F[GetPromptResponse]

  extension [F[_]](prompt: Prompt[F, Unit])
    def resolve: Resolved[F] = prompt.provide(())

  def builder[F[_]: Async]: PromptBuilder.PlainEmpty[F] =
    new PromptBuilder.PlainEmpty[F]

  def contextual[F[_]: Async, Ctx]: PromptBuilder.ContextualEmpty[F, Ctx] =
    new PromptBuilder.ContextualEmpty[F, Ctx]

  /**
   * Create a simple prompt with static messages.
   */
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

  /**
   * Create a dynamic prompt that generates messages based on arguments.
   */
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

  // ── Context-free (plain) builder ──────────────────────────────

  final class PlainEmpty[F[_]: Async]:
    def name(n: String): PlainBuilder[F] =
      new PlainBuilder[F](n, None, Nil)

  final class PlainBuilder[F[_]: Async] private[PromptBuilder] (
      promptName: String,
      promptDescription: Option[String],
      promptArguments: List[PromptArgument]
  ):

    private def copy(
        promptName: String = this.promptName,
        promptDescription: Option[String] = this.promptDescription,
        promptArguments: List[PromptArgument] = this.promptArguments
    ): PlainBuilder[F] =
      new PlainBuilder[F](promptName, promptDescription, promptArguments)

    def description(d: String): PlainBuilder[F] =
      copy(promptDescription = Some(d))

    def argument(name: String, description: Option[String] = None, required: Boolean = false): PlainBuilder[F] =
      copy(promptArguments = promptArguments :+ PromptArgument(name, description, required))

    def messages(msgs: List[PromptMessage]): Prompt[F, Unit] =
      Prompt.static[F](promptName, msgs, promptDescription, promptArguments)

    def generate(generator: Map[String, Json] => F[List[PromptMessage]]): Prompt[F, Unit] =
      Prompt.dynamic[F](promptName, generator, promptDescription, promptArguments)

  // ── Contextual builder ──────────────────────────────────────────

  final class ContextualEmpty[F[_]: Async, Ctx]:
    def name(n: String): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](n, None, Nil)

  final class ContextualBuilder[F[_]: Async, Ctx] private[PromptBuilder] (
      promptName: String,
      promptDescription: Option[String],
      promptArguments: List[PromptArgument]
  ):

    private def copy(
        promptName: String = this.promptName,
        promptDescription: Option[String] = this.promptDescription,
        promptArguments: List[PromptArgument] = this.promptArguments
    ): ContextualBuilder[F, Ctx] =
      new ContextualBuilder[F, Ctx](promptName, promptDescription, promptArguments)

    def description(d: String): ContextualBuilder[F, Ctx] =
      copy(promptDescription = Some(d))

    def argument(name: String, description: Option[String] = None, required: Boolean = false): ContextualBuilder[F, Ctx] =
      copy(promptArguments = promptArguments :+ PromptArgument(name, description, required))

    def generate(generator: (Ctx, Map[String, Json]) => F[List[PromptMessage]]): Prompt[F, Ctx] =
      val desc = promptDescription
      new Prompt[F, Ctx](
        name = promptName,
        description = promptDescription,
        arguments = promptArguments,
        handler = (ctx, args) => generator(ctx, args).map(messages => GetPromptResponse(desc, messages))
      )
