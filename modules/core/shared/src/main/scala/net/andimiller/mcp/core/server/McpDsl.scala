package net.andimiller.mcp.core.server

import cats.effect.kernel.Async

trait McpDsl[F[_]: Async]:

  def tool: ToolBuilder.PlainEmpty[F] = Tool.builder[F]

  def contextualTool[Ctx]: ToolBuilder.ContextualEmpty[F, Ctx] = Tool.contextual[F, Ctx]

  def resource: ResourceBuilder.PlainEmpty[F] = McpResource.builder[F]

  def contextualResource[Ctx]: ResourceBuilder.ContextualEmpty[F, Ctx] = McpResource.contextual[F, Ctx]

  def resourceTemplate: ResourceTemplateBuilder.PlainEmpty[F] = ResourceTemplate.builder[F]

  def contextualResourceTemplate[Ctx]: ResourceTemplateBuilder.ContextualEmpty[F, Ctx] = ResourceTemplate.contextual[F, Ctx]

  def prompt: PromptBuilder.PlainEmpty[F] = Prompt.builder[F]

  def contextualPrompt[Ctx]: PromptBuilder.ContextualEmpty[F, Ctx] = Prompt.contextual[F, Ctx]

  object path:
    def static(prefix: String): UriPath[Unit]   = UriPath.static(prefix)
    def named(name: String): UriPath[String]     = UriPath.named(name)
    def rest: UriPath[String]                    = UriPath.rest
