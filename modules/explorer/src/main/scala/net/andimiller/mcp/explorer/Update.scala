package net.andimiller.mcp.explorer

import cats.effect.IO
import io.circe.{Decoder as CirceDecoder, *}
import io.circe.parser.*
import net.andimiller.mcp.core.protocol.*
import tyrian.*
import tyrian.http.{Decoder as HttpDecoder, *}

object Update:

  private def httpDecode[A: CirceDecoder](onOk: A => Msg, onErr: String => Msg): HttpDecoder[Msg] =
    HttpDecoder[Msg](
      resp => McpClient.parseResp[A](resp.body) match
        case Right(a) => onOk(a)
        case Left(e)  => onErr(e),
      err => onErr(err.toString)
    )

  private def httpDecodeWithSession(onOk: (InitializeResponse, String) => Msg, onErr: String => Msg): HttpDecoder[Msg] =
    HttpDecoder[Msg](
      resp =>
        val sid = resp.headers.find(_._1.toLowerCase == "mcp-session-id").map(_._2).getOrElse("")
        McpClient.parseResp[InitializeResponse](resp.body) match
          case Right(a) => onOk(a, sid)
          case Left(e)  => onErr(e),
      err => onErr(err.toString)
    )

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.SetUrl(url) => (model.copy(serverUrl = url), Cmd.None)
    case Msg.Disconnect => (Model.init.copy(serverUrl = model.serverUrl), Cmd.None)
    case Msg.Connect =>
      val cmd = Http.send[IO, String, Msg](
        McpClient.initializeRequest(model.serverUrl),
        httpDecodeWithSession(
          (resp, sid) => Msg.InitializeResult(resp.protocolVersion, resp.serverInfo, resp.capabilities, sid),
          Msg.ConnectionError(_)
        )
      )
      (model.copy(connection = ConnectionStatus.Connecting), cmd)
    case Msg.InitializeResult(pv, si, cp, sid) =>
      val notifCmd = Http.send[IO, String, Msg](
        McpClient.notifRequest(model.serverUrl, "initialized", Json.obj(), sid),
        HttpDecoder[Msg](_ => Msg.NotificationReceived(Json.Null), _ => Msg.NotificationReceived(Json.Null))
      )
      val fetchCmds = Cmd.Batch(
        notifCmd,
        Http.send[IO, String, Msg](
          McpClient.listToolsRequest(model.serverUrl, sid),
          httpDecode[ListToolsResponse](r => Msg.ToolsLoaded(r.tools), Msg.ToolsError(_))
        ),
        Http.send[IO, String, Msg](
          McpClient.listResourcesRequest(model.serverUrl, sid),
          httpDecode[ListResourcesResponse](
            r => Msg.ResourcesLoaded(r.resources, Nil),
            _ => Msg.ResourcesLoaded(Nil, Nil)
          )
        ),
        Http.send[IO, String, Msg](
          McpClient.listTemplatesRequest(model.serverUrl, sid),
          httpDecode[ListResourceTemplatesResponse](
            r => Msg.ResourcesLoaded(Nil, r.resourceTemplates),
            _ => Msg.ResourcesLoaded(Nil, Nil)
          )
        ),
        Http.send[IO, String, Msg](
          McpClient.listPromptsRequest(model.serverUrl, sid),
          httpDecode[ListPromptsResponse](r => Msg.PromptsLoaded(r.prompts), Msg.PromptsError(_))
        )
      )
      (model.copy(
        connection = ConnectionStatus.Connected(pv, si, cp),
        sessionId = Some(sid),
        toolsLoading = true,
        resourcesLoading = true,
        promptsLoading = true
      ), fetchCmds)
    case Msg.ConnectionError(e) => (model.copy(connection = ConnectionStatus.Error(e)), Cmd.None)
    case Msg.ToolsLoaded(tools) =>
      val inputs = tools.map(t => (t.name, McpClient.schemaToTemplate(t.inputSchema))).toMap
      (model.copy(tools = tools, toolInputs = inputs, toolsLoading = false), Cmd.None)
    case Msg.ToolsError(e) =>
      (model.copy(toolsLoading = false, toolErrors = model.toolErrors + ("_list" -> e)), Cmd.None)
    case Msg.ResourcesLoaded(rs, ts) =>
      (model.copy(
        resources = if rs.nonEmpty then rs else model.resources,
        resourceTemplates = if ts.nonEmpty then ts else model.resourceTemplates,
        resourcesLoading = false
      ), Cmd.None)
    case Msg.PromptsLoaded(ps) =>
      val args = ps.map(p => (p.name, p.arguments.map(a => (a.name, "")).toMap)).toMap
      (model.copy(prompts = ps, promptArgs = args, promptsLoading = false), Cmd.None)
    case Msg.PromptsError(e) =>
      (model.copy(promptsLoading = false, promptErrors = model.promptErrors + ("_list" -> e)), Cmd.None)
    case Msg.SelectTab(t) => (model.copy(selectedTab = t), Cmd.None)
    case Msg.SelectTool(n) => (model.copy(selectedTool = Some(n)), Cmd.None)
    case Msg.SelectResource(u) => (model.copy(selectedResource = Some(u)), Cmd.None)
    case Msg.SelectPrompt(n) => (model.copy(selectedPrompt = Some(n)), Cmd.None)
    case Msg.UpdateToolInput(n, i) =>
      val err = parse(i).left.toOption.map(_.getMessage)
      (model.copy(toolInputs = model.toolInputs + (n -> i), toolErrors = err.fold(model.toolErrors - n)(e => model.toolErrors + (n -> e))), Cmd.None)
    case Msg.FormatToolInput(n) =>
      val fmt = model.toolInputs.get(n).flatMap(s => parse(s).toOption.map(_.spaces2))
      (model.copy(toolInputs = fmt.map(f => model.toolInputs + (n -> f)).getOrElse(model.toolInputs)), Cmd.None)
    case Msg.CallTool(n) =>
      model.sessionId match
        case Some(sid) =>
          val args = model.toolInputs.get(n).flatMap(s => parse(s).toOption).getOrElse(Json.obj())
          val cmd = Http.send[IO, String, Msg](
            McpClient.callToolRequest(model.serverUrl, n, args, sid),
            httpDecode[CallToolResponse](r => Msg.ToolResult(n, r), e => Msg.ToolError(n, e))
          )
          (model, cmd)
        case None => (model.copy(toolErrors = model.toolErrors + (n -> "Not connected")), Cmd.None)
    case Msg.ToolResult(n, r) => (model.copy(toolResults = model.toolResults + (n -> r)), Cmd.None)
    case Msg.ToolError(n, e) => (model.copy(toolErrors = model.toolErrors + (n -> e)), Cmd.None)
    case Msg.UpdateTemplateUri(t, u) =>
      (model.copy(templateInputs = model.templateInputs + (t -> u)), Cmd.None)
    case Msg.ReadResource(u) =>
      model.sessionId match
        case Some(sid) =>
          val cmd = Http.send[IO, String, Msg](
            McpClient.readResourceRequest(model.serverUrl, u, sid),
            httpDecode[ReadResourceResponse](r => Msg.ResourceResult(u, r), e => Msg.ResourceError(u, e))
          )
          (model, cmd)
        case None => (model.copy(resourceErrors = model.resourceErrors + (u -> "Not connected")), Cmd.None)
    case Msg.ResourceResult(u, r) => (model.copy(resourceContents = model.resourceContents + (u -> r)), Cmd.None)
    case Msg.ResourceError(u, e) => (model.copy(resourceErrors = model.resourceErrors + (u -> e)), Cmd.None)
    case Msg.UpdatePromptArg(p, a, v) =>
      val ca = model.promptArgs.getOrElse(p, Map.empty)
      (model.copy(promptArgs = model.promptArgs + (p -> (ca + (a -> v)))), Cmd.None)
    case Msg.GetPrompt(p) =>
      model.sessionId match
        case Some(sid) =>
          val args = model.promptArgs.getOrElse(p, Map.empty)
          val cmd = Http.send[IO, String, Msg](
            McpClient.getPromptRequest(model.serverUrl, p, args, sid),
            httpDecode[GetPromptResponse](r => Msg.PromptResult(p, r), e => Msg.PromptError(p, e))
          )
          (model, cmd)
        case None => (model.copy(promptErrors = model.promptErrors + (p -> "Not connected")), Cmd.None)
    case Msg.PromptResult(p, r) => (model.copy(promptResults = model.promptResults + (p -> r)), Cmd.None)
    case Msg.PromptError(p, e) => (model.copy(promptErrors = model.promptErrors + (p -> e)), Cmd.None)
    case Msg.InitializeError(e) => (model.copy(connection = ConnectionStatus.Error(e)), Cmd.None)
    case Msg.NotificationReceived(_) => (model, Cmd.None)
    case Msg.NoOp => (model, Cmd.None)
  }
