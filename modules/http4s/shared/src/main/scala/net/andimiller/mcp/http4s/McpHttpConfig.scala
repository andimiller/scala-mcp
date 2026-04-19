package net.andimiller.mcp.http4s

import com.comcast.ip4s.*

case class McpHttpConfig(
  host: Host = host"0.0.0.0",
  port: Port = port"8080",
  explorerEnabled: Boolean = false,
  rootRedirectToExplorer: Boolean = false
)