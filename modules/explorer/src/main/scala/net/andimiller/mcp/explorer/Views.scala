package net.andimiller.mcp.explorer

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import tyrian.*
import tyrian.Html.*

object Views:

  private def contentText(c: Content): String = c match
    case Content.Text(text) => text
    case Content.Image(_, mimeType) => s"[Image: $mimeType]"
    case Content.Audio(_, mimeType) => s"[Audio: $mimeType]"
    case Content.Resource(uri, _, text) => text.getOrElse(s"[Resource: $uri]")

  def view(model: Model): Html[Msg] =
    div(`class` := Styles.container)(
      header(model),
      if model.connection == ConnectionStatus.Disconnected || model.connection == ConnectionStatus.Connecting then connectView
      else mainContent(model)
    )

  private def header(model: Model): Html[Msg] =
    nav(`class` := Styles.header)(
      div(`class` := "navbar-brand")(
        span(`class` := "navbar-item has-text-weight-bold")("MCP Explorer")
      ),
      div(`class` := "navbar-end")(
        div(`class` := "navbar-item")(
          div(`class` := "field is-grouped")(
            div(`class` := "control is-expanded")(
              input(`class` := Styles.headerInput, `type` := "text", placeholder := "MCP Server URL", value := model.serverUrl, onInput(s => Msg.SetUrl(s)))
            ),
            div(`class` := "control")(
              model.connection match
                case ConnectionStatus.Disconnected => button(`class` := Styles.connectButton, onClick(Msg.Connect))("Connect")
                case ConnectionStatus.Connecting => button(`class` := Styles.disconnectButton)("Connecting...")
                case _ => button(`class` := Styles.disconnectButton, onClick(Msg.Disconnect))("Disconnect")
            ),
            div(`class` := "control")(
              span(`class` := (model.connection match
                case ConnectionStatus.Connected(_, _, _) => Styles.statusConnected
                case ConnectionStatus.Error(_) => Styles.statusError
                case ConnectionStatus.Connecting => Styles.statusConnecting
                case _ => ""))(
                model.connection match
                  case ConnectionStatus.Connected(_, info, _) => s"Connected: ${info.name}"
                  case ConnectionStatus.Error(msg) => s"Error: $msg"
                  case ConnectionStatus.Connecting => "Connecting..."
                  case _ => ""
              )
            )
          )
        )
      )
    )

  private def connectView: Html[Msg] =
    div(`class` := "hero is-medium is-flex-grow-1")(
      div(`class` := "hero-body has-text-centered")(
        p(`class` := "subtitle has-text-grey")("Enter an MCP server URL and click Connect")
      )
    )

  private def mainContent(model: Model): Html[Msg] =
    div(`class` := Styles.main)(
      sidebar(model),
      detailPanel(model)
    )

  private def sidebar(model: Model): Html[Msg] =
    div(`class` := Styles.sidebar)(
      div(`class` := Styles.tabs)(
        ul(
          li(`class` := (if model.selectedTab == Tab.Tools then Styles.activeTab else Styles.tab))(a(onClick(Msg.SelectTab(Tab.Tools)))("Tools")),
          li(`class` := (if model.selectedTab == Tab.Resources then Styles.activeTab else Styles.tab))(a(onClick(Msg.SelectTab(Tab.Resources)))("Resources")),
          li(`class` := (if model.selectedTab == Tab.Prompts then Styles.activeTab else Styles.tab))(a(onClick(Msg.SelectTab(Tab.Prompts)))("Prompts"))
        )
      ),
      div(`class` := Styles.list)(
        model.selectedTab match
          case Tab.Tools =>
            if model.toolsLoading then List(div(`class` := Styles.emptyState)("Loading..."))
            else model.tools.map(t => div(`class` := (if model.selectedTool == Some(t.name) then Styles.selectedItem else Styles.listItem), onClick(Msg.SelectTool(t.name)))(b(t.name)))
          case Tab.Resources =>
            if model.resourcesLoading then List(div(`class` := Styles.emptyState)("Loading..."))
            else
              (model.resources.map(r => div(`class` := (if model.selectedResource == Some(r.uri) then Styles.selectedItem else Styles.listItem), onClick(Msg.SelectResource(r.uri)))(b(r.name))) ++
              model.resourceTemplates.map(t => div(`class` := (if model.selectedResource == Some(t.uriTemplate) then Styles.selectedItem else Styles.listItem), onClick(Msg.SelectResource(t.uriTemplate)))(b(t.name))))
          case Tab.Prompts =>
            if model.promptsLoading then List(div(`class` := Styles.emptyState)("Loading..."))
            else model.prompts.map(p => div(`class` := (if model.selectedPrompt == Some(p.name) then Styles.selectedItem else Styles.listItem), onClick(Msg.SelectPrompt(p.name)))(b(p.name)))
      )
    )

  private def detailPanel(model: Model): Html[Msg] =
    div(`class` := Styles.detail)(
      model.selectedTab match
        case Tab.Tools => model.selectedTool.flatMap(n => model.tools.find(_.name == n)) match
          case Some(tool) => toolView(model, tool)
          case None => div(`class` := Styles.emptyState)("Select a tool")
        case Tab.Resources => model.selectedResource.flatMap(u => model.resources.find(_.uri == u).orElse(model.resourceTemplates.find(_.uriTemplate == u))) match
          case Some(r: ResourceDefinition) => resourceView(model, r)
          case Some(t: ResourceTemplateDefinition) => templateView(model, t)
          case None => div(`class` := Styles.emptyState)("Select a resource")
        case Tab.Prompts => model.selectedPrompt.flatMap(n => model.prompts.find(_.name == n)) match
          case Some(p) => promptView(model, p)
          case None => div(`class` := Styles.emptyState)("Select a prompt")
    )

  private def toolView(model: Model, tool: ToolDefinition): Html[Msg] = {
    val n = tool.name
    val hasErr = model.toolErrors.contains(n)
    div(
      div(`class` := Styles.detailTitle)(n), div(`class` := Styles.detailDescription)(tool.description),
      div(`class` := Styles.section)(div(`class` := Styles.sectionTitle)("Schema"), pre(`class` := Styles.resultBox)(tool.inputSchema.spaces2)),
      div(`class` := Styles.section)(
        div(`class` := Styles.sectionTitle)("Arguments"),
        textarea(`class` := (if hasErr then Styles.textareaError else Styles.textarea), value := model.toolInputs.getOrElse(n, "{}"), onInput(s => Msg.UpdateToolInput(n, s)))(),
        if hasErr then div(`class` := Styles.errorText)(model.toolErrors(n)) else span(),
        div(`class` := Styles.buttonRow)(
          button(`class` := Styles.formatButton, onClick(Msg.FormatToolInput(n)))("Format"),
          button(`class` := Styles.callButton, onClick(Msg.CallTool(n)))("Call")
        )
      ),
      model.toolResults.get(n) match
        case Some(r) => div(`class` := Styles.section)(div(`class` := Styles.sectionTitle)("Result"), div(`class` := Styles.resultBox)(r.content.map(contentText).mkString("\n")))
        case None => span()
    )
  }

  private def resourceView(model: Model, r: ResourceDefinition): Html[Msg] = {
    val u = r.uri
    div(
      div(`class` := Styles.detailTitle)(r.name), div(`class` := Styles.detailDescription)(r.description.getOrElse("")), div(`class` := "mb-2")(code(u)),
      if model.resourceErrors.contains(u) then div(`class` := Styles.errorText)(model.resourceErrors(u))
      else if model.resourceContents.contains(u) then
        div(
          button(`class` := Styles.callButton, onClick(Msg.ReadResource(u)))("Refresh"),
          div(`class` := Styles.section)(div(`class` := Styles.sectionTitle)("Content"), pre(`class` := Styles.resourceContent)(model.resourceContents(u).contents.map(_.text.getOrElse("[No text]")).mkString("\n")))
        )
      else button(`class` := Styles.callButton, onClick(Msg.ReadResource(u)))("Read")
    )
  }

  private def templateView(model: Model, t: ResourceTemplateDefinition): Html[Msg] = {
    val tpl = t.uriTemplate
    val uri = model.templateInputs.getOrElse(tpl, tpl)
    val readUri = model.templateInputs.getOrElse(tpl, "")
    div(
      div(`class` := Styles.detailTitle)(t.name),
      div(`class` := Styles.detailDescription)(t.description.getOrElse("")),
      div(`class` := "mb-2")(code(tpl)),
      div(`class` := Styles.section)(
        div(`class` := Styles.sectionTitle)("URI"),
        div(`class` := "field has-addons")(
          div(`class` := "control is-expanded")(
            input(`class` := "input", `type` := "text", placeholder := tpl, value := uri, onInput(s => Msg.UpdateTemplateUri(tpl, s)))
          ),
          div(`class` := "control")(
            button(`class` := Styles.callButton, onClick(Msg.ReadResource(readUri)))("Read")
          )
        )
      ),
      if model.resourceErrors.contains(readUri) then div(`class` := Styles.errorText)(model.resourceErrors(readUri))
      else if model.resourceContents.contains(readUri) then
        div(`class` := Styles.section)(div(`class` := Styles.sectionTitle)("Content"), pre(`class` := Styles.resourceContent)(model.resourceContents(readUri).contents.map(_.text.getOrElse("[No text]")).mkString("\n")))
      else span()
    )
  }

  private def promptView(model: Model, p: PromptDefinition): Html[Msg] = {
    val n = p.name
    div(
      div(`class` := Styles.detailTitle)(n), div(`class` := Styles.detailDescription)(p.description.getOrElse("")),
      if p.arguments.nonEmpty then div(`class` := Styles.section)(
        List(div(`class` := Styles.sectionTitle)("Arguments")) ++
        p.arguments.map(a => div(`class` := "field mb-3")(label(`class` := "label")(s"${a.name}${if a.required then " *" else ""}"), div(`class` := "control")(input(`class` := "input", `type` := "text", placeholder := a.description.getOrElse(""), value := model.promptArgs.getOrElse(n, Map.empty).getOrElse(a.name, ""), onInput(s => Msg.UpdatePromptArg(n, a.name, s))))))
      ) else span(),
      div(`class` := Styles.buttonRow)(button(`class` := Styles.callButton, onClick(Msg.GetPrompt(n)))("Get Prompt")),
      if model.promptErrors.contains(n) then div(`class` := Styles.errorText)(model.promptErrors(n))
      else if model.promptResults.contains(n) then div(`class` := Styles.section)(
        div(`class` := Styles.sectionTitle)("Messages"),
        div(`class` := Styles.promptMessages)(model.promptResults(n).messages.map(m => div(`class` := Styles.promptMessage)(div(`class` := Styles.promptRole)(m.role.toString), pre(`class` := "is-family-monospace")(contentText(m.content)))))
      )
      else span()
    )
  }
