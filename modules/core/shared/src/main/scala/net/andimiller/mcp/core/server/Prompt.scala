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

  def builder: PromptBuilder.Empty[Unit] =
    new PromptBuilder.Empty[Unit]

  def contextual[Ctx]: PromptBuilder.Empty[Ctx] =
    new PromptBuilder.Empty[Ctx]

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

  final class Empty[Ctx]:
    def name(n: String): Builder[Ctx] =
      new Builder[Ctx](n, None, Nil)

  final class Builder[Ctx] private[PromptBuilder] (
      private[PromptBuilder] val promptName: String,
      private[PromptBuilder] val promptDescription: Option[String],
      private[PromptBuilder] val promptArguments: List[PromptArgument]
  ):

    private def copy(
        promptName: String = this.promptName,
        promptDescription: Option[String] = this.promptDescription,
        promptArguments: List[PromptArgument] = this.promptArguments
    ): Builder[Ctx] =
      new Builder[Ctx](promptName, promptDescription, promptArguments)

    def description(d: String): Builder[Ctx] =
      copy(promptDescription = Some(d))

    def argument(name: String, description: Option[String] = None, required: Boolean = false): Builder[Ctx] =
      copy(promptArguments = promptArguments :+ PromptArgument(name, description, required))

  extension [Ctx](b: Builder[Ctx])
    def generate[F[_]: Async](generator: (Ctx, Map[String, Json]) => F[List[PromptMessage]]): Prompt[F, Ctx] =
      val desc = b.promptDescription
      new Prompt[F, Ctx](
        name = b.promptName,
        description = b.promptDescription,
        arguments = b.promptArguments,
        handler = (ctx, args) => generator(ctx, args).map(messages => GetPromptResponse(desc, messages))
      )

  extension (b: Builder[Unit])
    def messages[F[_]: Async](msgs: List[PromptMessage]): Prompt[F, Unit] =
      Prompt.static[F](b.promptName, msgs, b.promptDescription, b.promptArguments)

    def generate[F[_]: Async](generator: Map[String, Json] => F[List[PromptMessage]]): Prompt[F, Unit] =
      Prompt.dynamic[F](b.promptName, generator, b.promptDescription, b.promptArguments)
