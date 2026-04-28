package net.andimiller.mcp.core.server

import munit.FunSuite
import net.andimiller.mcp.core.protocol.*

class CapabilityTrackerSuite extends FunSuite:

  test("empty tracker has all capabilities as None") {
    val t = CapabilityTracker.empty
    assertEquals(t.tools, None)
    assertEquals(t.resources, None)
    assertEquals(t.prompts, None)
    assertEquals(t.logging, None)
  }

  test("withToolAdded enables tools capability") {
    val t = CapabilityTracker.empty.withToolAdded
    assertEquals(t.tools, Some(ToolCapabilities()))
  }

  test("withResourceAdded enables resources capability") {
    val t = CapabilityTracker.empty.withResourceAdded
    assertEquals(t.resources, Some(ResourceCapabilities()))
  }

  test("withPromptAdded enables prompts capability") {
    val t = CapabilityTracker.empty.withPromptAdded
    assertEquals(t.prompts, Some(PromptCapabilities()))
  }

  test("withResourceSubscriptions sets subscribe flag, preserves auto-add") {
    val t = CapabilityTracker.empty.withResourceAdded.withResourceSubscriptions
    assertEquals(t.resources, Some(ResourceCapabilities(subscribe = Some(true))))
  }

  test("withResourceSubscriptions before withResourceAdded still works (auto-creates)") {
    val t = CapabilityTracker.empty.withResourceSubscriptions
    assertEquals(t.resources, Some(ResourceCapabilities(subscribe = Some(true))))
  }

  test("withToolNotifications sets listChanged on tools") {
    val t = CapabilityTracker.empty.withToolNotifications
    assertEquals(t.tools, Some(ToolCapabilities(listChanged = Some(true))))
  }

  test("withResourceNotifications sets listChanged on resources without overriding subscribe") {
    val t = CapabilityTracker.empty.withResourceSubscriptions.withResourceNotifications
    assertEquals(
      t.resources,
      Some(ResourceCapabilities(subscribe = Some(true), listChanged = Some(true)))
    )
  }

  test("withPromptNotifications sets listChanged on prompts") {
    val t = CapabilityTracker.empty.withPromptNotifications
    assertEquals(t.prompts, Some(PromptCapabilities(listChanged = Some(true))))
  }

  test("withLogging enables logging capability") {
    val t = CapabilityTracker.empty.withLogging
    assertEquals(t.logging, Some(LoggingCapabilities()))
  }

  test("withToolAdded is idempotent — does not overwrite existing notifications") {
    val t = CapabilityTracker.empty.withToolNotifications.withToolAdded
    assertEquals(t.tools, Some(ToolCapabilities(listChanged = Some(true))))
  }

  test("toServerCapabilities reflects the tracker state") {
    val t   = CapabilityTracker.empty.withToolAdded.withResourceSubscriptions.withLogging
    val caps = t.toServerCapabilities
    assertEquals(caps.tools, Some(ToolCapabilities()))
    assertEquals(caps.resources, Some(ResourceCapabilities(subscribe = Some(true))))
    assertEquals(caps.prompts, None)
    assertEquals(caps.logging, Some(LoggingCapabilities()))
  }
