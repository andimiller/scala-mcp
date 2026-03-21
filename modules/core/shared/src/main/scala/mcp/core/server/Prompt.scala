package mcp.core.server

import cats.effect.kernel.Async
import cats.syntax.functor.*
import io.circe.Json
import mcp.core.protocol.*
import mcp.core.protocol.content.Content

/**
 * Prompt builder and helper functions for creating prompt handlers.
 */
object Prompt:

  /**
   * Create a simple prompt with static messages.
   */
  def static[F[_]: Async](
    promptName: String,
    messages: List[PromptMessage],
    promptDescription: Option[String] = None,
    promptArguments: List[PromptArgument] = Nil
  ): PromptHandler[F] =
    new PromptHandler[F]:
      def name: String = promptName
      override def description: Option[String] = promptDescription
      override def arguments: List[PromptArgument] = promptArguments
      def get(args: Map[String, Json]): F[GetPromptResponse] =
        Async[F].pure(GetPromptResponse(promptDescription, messages))

  /**
   * Create a dynamic prompt that generates messages based on arguments.
   */
  def dynamic[F[_]: Async](
    promptName: String,
    generator: Map[String, Json] => F[List[PromptMessage]],
    promptDescription: Option[String] = None,
    promptArguments: List[PromptArgument] = Nil
  ): PromptHandler[F] =
    new PromptHandler[F]:
      def name: String = promptName
      override def description: Option[String] = promptDescription
      override def arguments: List[PromptArgument] = promptArguments
      def get(args: Map[String, Json]): F[GetPromptResponse] =
        generator(args).map(messages => GetPromptResponse(promptDescription, messages))

  /**
   * Fluent builder for prompts.
   */
  def builder[F[_]: Async]: PromptBuilder[F] = new PromptBuilder[F]

/**
 * Fluent builder for constructing prompt handlers.
 */
class PromptBuilder[F[_]: Async]:
  private var promptName: Option[String] = None
  private var promptDescription: Option[String] = None
  private var promptArguments: List[PromptArgument] = Nil

  def name(n: String): this.type =
    promptName = Some(n)
    this

  def description(d: String): this.type =
    promptDescription = Some(d)
    this

  def argument(name: String, description: Option[String] = None, required: Boolean = false): this.type =
    promptArguments = promptArguments :+ PromptArgument(name, description, required)
    this

  def staticMessages(messages: List[PromptMessage]): PromptHandler[F] =
    require(promptName.isDefined, "Prompt name is required")

    Prompt.static[F](
      promptName.get,
      messages,
      promptDescription,
      promptArguments
    )

  def dynamicMessages(generator: Map[String, Json] => F[List[PromptMessage]]): PromptHandler[F] =
    require(promptName.isDefined, "Prompt name is required")

    Prompt.dynamic[F](
      promptName.get,
      generator,
      promptDescription,
      promptArguments
    )

  /** Helper to add a user message */
  def userMessage(content: String): PromptMessage =
    PromptMessage.user(content)

  /** Helper to add an assistant message */
  def assistantMessage(content: String): PromptMessage =
    PromptMessage.assistant(content)
