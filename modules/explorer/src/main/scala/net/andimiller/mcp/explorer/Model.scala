package net.andimiller.mcp.explorer

import net.andimiller.mcp.core.protocol.*
import org.scalajs.dom

enum ConnectionStatus:

  case Disconnected

  case Connecting

  case Connected(
      protocolVersion: String,
      serverInfo: Implementation,
      capabilities: ServerCapabilities
  )

  case Error(message: String)

enum Tab:

  case Tools

  case Resources

  case Prompts

case class Model(
    serverUrl: String,
    connection: ConnectionStatus,
    sessionId: Option[String],
    selectedTab: Tab,
    selectedTool: Option[String],
    selectedResource: Option[String],
    selectedPrompt: Option[String],
    tools: List[ToolDefinition],
    resources: List[ResourceDefinition],
    resourceTemplates: List[ResourceTemplateDefinition],
    prompts: List[PromptDefinition],
    toolInputs: Map[String, String],
    toolErrors: Map[String, String],
    toolResults: Map[String, CallToolResponse],
    resourceContents: Map[String, ReadResourceResponse],
    resourceErrors: Map[String, String],
    templateInputs: Map[String, String],
    promptArgs: Map[String, Map[String, String]],
    promptErrors: Map[String, String],
    promptResults: Map[String, GetPromptResponse],
    toolsLoading: Boolean,
    resourcesLoading: Boolean,
    promptsLoading: Boolean
)

object Model:

  def init: Model = Model(
    serverUrl = s"${dom.window.location.origin}/mcp",
    connection = ConnectionStatus.Disconnected,
    sessionId = None,
    selectedTab = Tab.Tools,
    selectedTool = None,
    selectedResource = None,
    selectedPrompt = None,
    tools = Nil,
    resources = Nil,
    resourceTemplates = Nil,
    prompts = Nil,
    toolInputs = Map.empty,
    toolErrors = Map.empty,
    toolResults = Map.empty,
    resourceContents = Map.empty,
    resourceErrors = Map.empty,
    templateInputs = Map.empty,
    promptArgs = Map.empty,
    promptErrors = Map.empty,
    promptResults = Map.empty,
    toolsLoading = false,
    resourcesLoading = false,
    promptsLoading = false
  )
