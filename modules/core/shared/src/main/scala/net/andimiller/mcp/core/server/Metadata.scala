package net.andimiller.mcp.core.server

import net.andimiller.mcp.core.protocol.Icon

import io.circe.Json
import io.circe.JsonObject

/** Cross-cutting metadata shared by Tool, Resource, ResourceTemplate, Prompt, and Implementation builders.
  *
  * Use the fluent API to construct a value (`Metadata.empty.title(...).icon(...).meta(...)`), then pass it to a
  * builder's `.metadata(m)` setter to bulk-apply. The aggregate covers only the fields every entity supports;
  * entity-specific fields (`annotations`, `execution`, `size`, `websiteUrl`, `description`) are set via flat setters.
  */
case class Metadata(
    title: Option[String] = None,
    icons: List[Icon] = Nil,
    meta: Option[JsonObject] = None
):

  def title(t: String): Metadata = copy(title = Some(t))

  /** Append a single icon. */
  def icon(i: Icon): Metadata = copy(icons = icons :+ i)

  /** Replace the icon list. */
  def icons(xs: List[Icon]): Metadata = copy(icons = xs)

  /** Set the full `_meta` object, replacing any previous value. */
  def meta(m: JsonObject): Metadata = copy(meta = Some(m))

  /** Add a single `_meta` key. Per spec, prefixed keys must follow reverse-DNS form. */
  def meta(key: String, value: Json): Metadata =
    val base = meta.getOrElse(JsonObject.empty)
    copy(meta = Some(base.add(key, value)))

  /** Returns `Some(icons)` if non-empty, else `None`. Matches the protocol case-class field shape. */
  def iconsOpt: Option[List[Icon]] = Option.when(icons.nonEmpty)(icons)

object Metadata:

  val empty: Metadata = Metadata()
