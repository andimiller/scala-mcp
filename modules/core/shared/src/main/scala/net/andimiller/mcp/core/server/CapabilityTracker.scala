package net.andimiller.mcp.core.server

import net.andimiller.mcp.core.protocol.*

case class CapabilityTracker(
    tools: Option[ToolCapabilities] = None,
    resources: Option[ResourceCapabilities] = None,
    prompts: Option[PromptCapabilities] = None,
    logging: Option[LoggingCapabilities] = None
):

  // ── Auto-set on handler add ──────────────────────────────────────

  def withToolAdded: CapabilityTracker =
    copy(tools = tools.orElse(Some(ToolCapabilities())))

  def withResourceAdded: CapabilityTracker =
    copy(resources = resources.orElse(Some(ResourceCapabilities())))

  def withPromptAdded: CapabilityTracker =
    copy(prompts = prompts.orElse(Some(PromptCapabilities())))

  // ── Enable capability flags (merge, don't replace) ───────────────

  def withToolNotifications: CapabilityTracker =
    copy(tools = Some(tools.getOrElse(ToolCapabilities()).copy(listChanged = Some(true))))

  def withResourceSubscriptions: CapabilityTracker =
    copy(resources = Some(resources.getOrElse(ResourceCapabilities()).copy(subscribe = Some(true))))

  def withResourceNotifications: CapabilityTracker =
    copy(resources = Some(resources.getOrElse(ResourceCapabilities()).copy(listChanged = Some(true))))

  def withPromptNotifications: CapabilityTracker =
    copy(prompts = Some(prompts.getOrElse(PromptCapabilities()).copy(listChanged = Some(true))))

  def withLogging: CapabilityTracker =
    copy(logging = Some(LoggingCapabilities()))

  // ── Convert to protocol type ─────────────────────────────────────

  def toServerCapabilities: ServerCapabilities =
    ServerCapabilities(
      tools = tools,
      resources = resources,
      prompts = prompts,
      logging = logging
    )

object CapabilityTracker:
  val empty: CapabilityTracker = CapabilityTracker()
