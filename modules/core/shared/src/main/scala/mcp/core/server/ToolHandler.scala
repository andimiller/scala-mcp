package mcp.core.server

import io.circe.Json
import mcp.core.protocol.*

/**
 * Handler for a single tool.
 *
 * Tools are executable functions that can be called by the client.
 */
trait ToolHandler[F[_]]:
  /** Unique name of the tool */
  def name: String

  /** Human-readable description of what the tool does */
  def description: String

  /** JSON Schema describing the tool's input parameters */
  def inputSchema: Json

  /** JSON Schema describing the tool's output */
  def outputSchema: Json

  /** Execute the tool with the given arguments */
  def handle(arguments: Json): F[ToolResult]

/**
 * Handler for a resource.
 *
 * Resources are readable data sources (files, URLs, database entries, etc.)
 */
trait ResourceHandler[F[_]]:
  /** Unique URI identifying this resource */
  def uri: String

  /** Human-readable name */
  def name: String

  /** Optional description */
  def description: Option[String] = None

  /** Optional MIME type */
  def mimeType: Option[String] = None

  /** Read the resource content */
  def read(): F[ResourceContent]

/**
 * Handler for a prompt template.
 *
 * Prompts are pre-defined message templates that can be instantiated with arguments.
 */
trait PromptHandler[F[_]]:
  /** Unique name of the prompt */
  def name: String

  /** Human-readable description */
  def description: Option[String] = None

  /** List of arguments this prompt accepts */
  def arguments: List[PromptArgument] = Nil

  /** Generate the prompt messages with the given arguments */
  def get(arguments: Map[String, Json]): F[GetPromptResponse]
