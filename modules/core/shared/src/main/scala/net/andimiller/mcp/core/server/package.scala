package net.andimiller.mcp.core.server

def tool: ToolBuilder.Empty[Unit] = Tool.builder

def contextualTool[Ctx]: ToolBuilder.Empty[Ctx] = Tool.contextual[Ctx]

def resource: ResourceBuilder.Empty[Unit] = McpResource.builder

def contextualResource[Ctx]: ResourceBuilder.Empty[Ctx] = McpResource.contextual[Ctx]

def resourceTemplate: ResourceTemplateBuilder.Empty[Unit] = ResourceTemplate.builder

def contextualResourceTemplate[Ctx]: ResourceTemplateBuilder.Empty[Ctx] = ResourceTemplate.contextual[Ctx]

def prompt: PromptBuilder.Empty[Unit] = Prompt.builder

def contextualPrompt[Ctx]: PromptBuilder.Empty[Ctx] = Prompt.contextual[Ctx]

object path:

  def static(prefix: String): UriPath[Unit] = UriPath.static(prefix)

  def named(name: String): UriPath[String] = UriPath.named(name)

  def rest: UriPath[String] = UriPath.rest
