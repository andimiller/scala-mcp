import sbt._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._

object LlmsTxt {

  final case class DocPage(
      relPath: String, // POSIX-style, relative to mdocOut, e.g. "getting-started/quick-start.md"
      title: String,
      description: String,
      body: String
  )

  final case class DirConf(title: Option[String], navOrder: Seq[String])

  def run(
      mdocOut: File,
      siteOut: File,
      siteUrl: String,
      projectName: String,
      tagline: String,
      log: Logger
  ): Unit = {
    if (!mdocOut.exists()) {
      log.warn(s"[llms] mdoc output not found at $mdocOut — skipping LLM artifact generation")
      return
    }
    if (!siteOut.exists()) {
      log.warn(s"[llms] laika output not found at $siteOut — skipping LLM artifact generation")
      return
    }

    val rootCfg = readDirectoryConf(mdocOut)
    val mdFiles = walkMarkdown(mdocOut)

    val pages: Seq[DocPage] = mdFiles.map { f =>
      val rel   = mdocOut.toPath.relativize(f.toPath).toString.replace(File.separatorChar, '/')
      val raw   = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
      val title = extractTitle(raw).getOrElse(deriveFallbackTitle(rel))
      val descr = extractDescription(raw).getOrElse("")
      DocPage(rel, title, descr, raw)
    }

    val sectionOf: DocPage => String = p => {
      val i = p.relPath.indexOf('/')
      if (i < 0) "" else p.relPath.substring(0, i)
    }
    val bySection: Map[String, Seq[DocPage]] = pages.groupBy(sectionOf)

    val orderedSectionKeys: Seq[String] = {
      val explicit = rootCfg.navOrder
        .map(stripMdSuffix)
        .filter(k => bySection.contains(k))
      val rest = bySection.keys.filterNot(k => explicit.contains(k) || k.isEmpty).toSeq.sorted
      explicit.filter(_.nonEmpty) ++ rest
    }

    val rootPages: Seq[DocPage] = {
      val raw = bySection.getOrElse("", Nil)
      orderPages(raw, rootCfg.navOrder, _.relPath)
    }

    val sectionPages: Seq[(String, String, Seq[DocPage])] = orderedSectionKeys.map { sec =>
      val cfg     = readDirectoryConf(new File(mdocOut, sec))
      val ordered = orderPages(bySection(sec), cfg.navOrder, p => p.relPath.stripPrefix(sec + "/"))
      (sec, cfg.title.getOrElse(humanise(sec)), ordered)
    }

    writeLlmsTxt(siteOut, siteUrl, projectName, tagline, rootPages, sectionPages, log)
    writeLlmsFullTxt(siteOut, projectName, tagline, rootPages, sectionPages, log)
    copyRawMarkdown(siteOut, pages, log)
    injectAlternateLinks(siteOut, pages, log)
  }

  // ---------- writers ----------

  private def writeLlmsTxt(
      siteOut: File,
      siteUrl: String,
      projectName: String,
      tagline: String,
      rootPages: Seq[DocPage],
      sectionPages: Seq[(String, String, Seq[DocPage])],
      log: Logger
  ): Unit = {
    val sb = new StringBuilder
    sb.append(s"# $projectName\n\n")
    sb.append(s"> $tagline\n\n")
    sb.append("## Documentation\n\n")
    rootPages.foreach(p => sb.append(formatLink(p, siteUrl)).append('\n'))
    if (rootPages.nonEmpty) sb.append('\n')
    sectionPages.foreach { case (_, sectionTitle, pages) =>
      sb.append(s"### $sectionTitle\n")
      pages.foreach(p => sb.append(formatLink(p, siteUrl)).append('\n'))
      sb.append('\n')
    }
    val out = new File(siteOut, "llms.txt")
    Files.write(out.toPath, sb.toString.getBytes(StandardCharsets.UTF_8))
    val total = rootPages.size + sectionPages.map(_._3.size).sum
    log.info(s"[llms] wrote ${out.getName} ($total pages)")
  }

  private def writeLlmsFullTxt(
      siteOut: File,
      projectName: String,
      tagline: String,
      rootPages: Seq[DocPage],
      sectionPages: Seq[(String, String, Seq[DocPage])],
      log: Logger
  ): Unit = {
    val sb = new StringBuilder
    sb.append(s"# $projectName\n\n")
    sb.append(s"> $tagline\n")
    val all: Seq[DocPage] = rootPages ++ sectionPages.flatMap(_._3)
    all.foreach { p =>
      sb.append("\n\n---\n\n")
      sb.append(s"<!-- source: ${p.relPath} -->\n\n")
      sb.append(p.body.trim)
      sb.append('\n')
    }
    val out = new File(siteOut, "llms-full.txt")
    Files.write(out.toPath, sb.toString.getBytes(StandardCharsets.UTF_8))
    log.info(s"[llms] wrote ${out.getName} (${all.size} pages, ${out.length()} bytes)")
  }

  private def copyRawMarkdown(siteOut: File, pages: Seq[DocPage], log: Logger): Unit = {
    pages.foreach { p =>
      val dest = new File(siteOut, p.relPath)
      Files.createDirectories(dest.getParentFile.toPath)
      Files.write(dest.toPath, p.body.getBytes(StandardCharsets.UTF_8))
    }
    log.info(s"[llms] copied ${pages.size} raw markdown files into ${siteOut.getName}/")
  }

  // Inject `<link rel="alternate" type="text/markdown" ...>` into each rendered HTML page,
  // pointing at its sibling .md file. Done as a post-process because Laika templates only
  // expose the absolute virtual sourcePath; we want a relative href.
  private def injectAlternateLinks(siteOut: File, pages: Seq[DocPage], log: Logger): Unit = {
    val titleClose = "</title>"
    var injected   = 0
    var skipped    = 0
    pages.foreach { p =>
      val htmlPath = p.relPath.stripSuffix(".md") + ".html"
      val htmlFile = new File(siteOut, htmlPath)
      if (!htmlFile.exists()) skipped += 1
      else {
        val mdName = p.relPath.split('/').last
        val link   =
          s"""<link rel="alternate" type="text/markdown" href="$mdName" title="Markdown source"/>"""
        val current = new String(Files.readAllBytes(htmlFile.toPath), StandardCharsets.UTF_8)
        if (current.contains("""rel="alternate" type="text/markdown"""")) {
          // Already present (idempotent re-run) — leave alone.
          ()
        } else {
          val idx = current.indexOf(titleClose)
          if (idx < 0) skipped += 1
          else {
            val cut    = idx + titleClose.length
            val update = current.substring(0, cut) + "\n  " + link + current.substring(cut)
            Files.write(htmlFile.toPath, update.getBytes(StandardCharsets.UTF_8))
            injected += 1
          }
        }
      }
    }
    log.info(s"[llms] injected alternate links into $injected html pages (skipped $skipped)")
  }

  private def formatLink(p: DocPage, siteUrl: String): String = {
    val base = siteUrl.stripSuffix("/")
    val url  = s"$base/${p.relPath}"
    if (p.description.isEmpty) s"- [${p.title}]($url)"
    else s"- [${p.title}]($url): ${p.description}"
  }

  // ---------- markdown parsing ----------

  private def splitLines(md: String): Iterator[String] =
    md.split("\\R", -1).iterator

  private def extractTitle(md: String): Option[String] =
    splitLines(md).find(_.startsWith("# ")).map(_.stripPrefix("# ").trim)

  private def extractDescription(md: String): Option[String] = {
    val lines  = splitLines(md)
    var seenH1 = false
    while (lines.hasNext) {
      val ln = lines.next()
      if (!seenH1) {
        if (ln.startsWith("# ")) seenH1 = true
      } else {
        val trimmed = ln.trim
        if (trimmed.nonEmpty && !trimmed.startsWith("#") && !trimmed.startsWith("```")) {
          val sb = new StringBuilder
          sb.append(trimmed)
          var done = false
          while (!done && lines.hasNext) {
            val cont = lines.next()
            if (cont.trim.isEmpty) done = true
            else { sb.append(' '); sb.append(cont.trim) }
          }
          return Some(stripInlineMarkdown(sb.toString))
        }
      }
    }
    None
  }

  private def stripInlineMarkdown(s: String): String =
    s.replaceAll("""\[([^\]]+)\]\([^)]+\)""", "$1")
      .replaceAll("""\*\*([^*]+)\*\*""", "$1")
      .replaceAll("""\*([^*]+)\*""", "$1")
      .replaceAll("""`([^`]+)`""", "$1")
      .replaceAll("""\s+""", " ")
      .trim

  private def deriveFallbackTitle(relPath: String): String = {
    val base = relPath.split('/').last.stripSuffix(".md")
    humanise(base)
  }

  private def humanise(s: String): String =
    s.split("[-_]").map(_.capitalize).mkString(" ")

  // ---------- directory.conf parsing (crude, sufficient for our schema) ----------

  private def readDirectoryConf(dir: File): DirConf = {
    val f = new File(dir, "directory.conf")
    if (!f.exists()) DirConf(None, Nil)
    else {
      val text    = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
      val titleRe = """laika\.title\s*=\s*"([^"]+)"""".r
      val orderRe = """(?s)laika\.navigationOrder\s*=\s*\[([^\]]*)\]""".r
      val title   = titleRe.findFirstMatchIn(text).map(_.group(1))
      val order   = orderRe
        .findFirstMatchIn(text)
        .map { m =>
          m.group(1)
            .split("[\\s,]+")
            .iterator
            .map(_.trim.stripPrefix("\"").stripSuffix("\""))
            .filter(_.nonEmpty)
            .toVector
        }
        .getOrElse(Vector.empty)
      DirConf(title, order)
    }
  }

  // ---------- ordering ----------

  private def orderPages(pages: Seq[DocPage], navOrder: Seq[String], keyOf: DocPage => String): Seq[DocPage] = {
    if (navOrder.isEmpty) pages.sortBy(_.relPath)
    else {
      val orderIdx: String => Int = k =>
        navOrder.indexOf(k) match {
          case -1 => Int.MaxValue
          case i  => i
        }
      val withIdx = pages.map(p => (orderIdx(keyOf(p)), p.relPath, p))
      withIdx.sortBy { case (i, path, _) => (i, path) }.map(_._3)
    }
  }

  private def stripMdSuffix(s: String): String =
    if (s.endsWith(".md")) s.dropRight(3) else s

  // ---------- file walk ----------

  private def walkMarkdown(root: File): Seq[File] = {
    val stream = Files.walk(root.toPath)
    try
      stream
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".md"))
        .map(_.toFile)
        .toVector
    finally stream.close()
  }

}
