package net.andimiller.mcp.explorer

import io.circe.Json
import net.andimiller.mcp.core.protocol.*

enum Msg:
  // Connection
  case SetUrl(url: String)
  case Connect
  case Disconnect
  case InitializeResult(
    protocolVersion: String,
    serverInfo: Implementation,
    capabilities: ServerCapabilities,
    sessionId: String
  )
  case InitializeError(error: String)
  case ConnectionError(error: String)

  // Lists loaded
  case ToolsLoaded(tools: List[ToolDefinition])
  case ToolsError(error: String)
  case ResourcesLoaded(resources: List[ResourceDefinition], templates: List[ResourceTemplateDefinition])
  case PromptsLoaded(prompts: List[PromptDefinition])
  case PromptsError(error: String)

  // Navigation
  case SelectTab(tab: Tab)
  case SelectTool(name: String)
  case SelectResource(uri: String)
  case SelectPrompt(name: String)

  // Tools
  case UpdateToolInput(name: String, input: String)
  case FormatToolInput(name: String)
  case CallTool(name: String)
  case ToolResult(name: String, result: CallToolResponse)
  case ToolError(name: String, error: String)

  // Resources
  case UpdateTemplateUri(template: String, uri: String)
  case ReadResource(uri: String)
  case ResourceResult(uri: String, result: ReadResourceResponse)
  case ResourceError(uri: String, error: String)

  // Prompts
  case UpdatePromptArg(prompt: String, arg: String, value: String)
  case GetPrompt(prompt: String)
  case PromptResult(prompt: String, result: GetPromptResponse)
  case PromptError(prompt: String, error: String)

  // SSE Notifications
  case NotificationReceived(notification: Json)

  case NoOp
