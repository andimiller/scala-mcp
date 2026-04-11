package net.andimiller.mcp.examples.rag

import fs2.io.file.Path

object Chunker:

  private val extensionToLanguage: Map[String, String] = Map(
    "scala" -> "scala",
    "java"  -> "java",
    "py"    -> "python",
    "js"    -> "javascript",
    "ts"    -> "typescript",
    "jsx"   -> "javascript",
    "tsx"   -> "typescript",
    "rs"    -> "rust",
    "go"    -> "go",
    "rb"    -> "ruby",
    "c"     -> "c",
    "cpp"   -> "cpp",
    "h"     -> "c",
    "hpp"   -> "cpp",
    "cs"    -> "csharp",
    "kt"    -> "kotlin",
    "swift" -> "swift",
    "sh"    -> "shell",
    "bash"  -> "shell",
    "zsh"   -> "shell",
    "sql"   -> "sql",
    "md"    -> "markdown",
    "txt"   -> "text",
    "json"  -> "json",
    "yaml"  -> "yaml",
    "yml"   -> "yaml",
    "toml"  -> "toml",
    "xml"   -> "xml",
    "html"  -> "html",
    "css"   -> "css",
    "sbt"   -> "sbt",
    "conf"  -> "config",
    "cfg"   -> "config",
    "ini"   -> "config",
    "properties" -> "config"
  )

  val defaultChunkSize: Int    = 10
  val defaultOverlap: Int      = 5
  val maxFileSizeBytes: Long   = 1024 * 1024 // 1 MB

  def detectLanguage(path: Path): Option[String] =
    val name = path.fileName.toString
    if name.startsWith(".") then None
    else
      val ext = name.lastIndexOf('.') match
        case -1 => None
        case i  => Some(name.substring(i + 1).toLowerCase)
      ext.flatMap(extensionToLanguage.get)

  def isIndexable(path: Path): Boolean =
    detectLanguage(path).isDefined

  def isExtensionAllowed(path: Path, extensions: Option[List[String]]): Boolean =
    extensions match
      case None | Some(Nil) => true
      case Some(exts) =>
        val name = path.fileName.toString
        name.lastIndexOf('.') match
          case -1 => false
          case i  => exts.contains(name.substring(i + 1).toLowerCase)

  def chunk(
    content: String,
    chunkSize: Int = defaultChunkSize,
    overlap: Int = defaultOverlap
  ): Vector[(Int, Int, String)] =
    val lines = content.linesIterator.toVector
    if lines.isEmpty then Vector.empty
    else
      val step = math.max(1, chunkSize - overlap)
      val starts = (0 until lines.size by step).toVector
      starts.map { start =>
        val end       = math.min(start + chunkSize, lines.size)
        val text      = lines.slice(start, end).mkString("\n")
        val startLine = start + 1  // 1-indexed
        val endLine   = end        // inclusive end
        (startLine, endLine, text)
      }.filter(_._3.trim.nonEmpty)
