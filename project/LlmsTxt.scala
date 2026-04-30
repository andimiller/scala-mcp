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
    postProcessHtml(siteOut, pages, siteUrl, log)
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

  // Post-process each rendered HTML page to inject:
  //   1. `<link rel="alternate" type="text/markdown" ...>` after </title> — for browsers
  //      and crawlers that read head metadata.
  //   2. A small `[md]` link as the first child of the page's `<h1 class="title">`. This
  //      sits inside the article body, so AI content extractors keep it; CSS floats it
  //      right and dims it so humans can ignore it but click through if they want the
  //      raw markdown.
  // Done here rather than in the Laika template because templates only expose the
  // absolute virtual sourcePath, and we need relative hrefs plus CSS-classed markup.
  private def postProcessHtml(siteOut: File, pages: Seq[DocPage], siteUrl: String, log: Logger): Unit = {
    val titleClose = "</title>"
    val h1Marker   = """class="title">"""

    var linksInjected   = 0
    var mdLinksInjected = 0
    var skipped         = 0

    pages.foreach { p =>
      val htmlPath = p.relPath.stripSuffix(".md") + ".html"
      val htmlFile = new File(siteOut, htmlPath)
      if (!htmlFile.exists()) skipped += 1
      else {
        val mdName  = p.relPath.split('/').last
        var content = new String(Files.readAllBytes(htmlFile.toPath), StandardCharsets.UTF_8)
        var changed = false

        if (!content.contains("""rel="alternate" type="text/markdown"""")) {
          val link =
            s"""<link rel="alternate" type="text/markdown" href="$mdName" title="Markdown source"/>"""
          val idx = content.indexOf(titleClose)
          if (idx >= 0) {
            val cut = idx + titleClose.length
            content = content.substring(0, cut) + "\n  " + link + content.substring(cut)
            linksInjected += 1
            changed = true
          }
        }

        if (!content.contains("""class="md-link"""")) {
          val mdLink =
            s"""<a class="md-link" href="$mdName" title="View this page as raw Markdown">[md]</a>"""
          val idx = content.indexOf(h1Marker)
          if (idx >= 0) {
            val cut = idx + h1Marker.length
            content = content.substring(0, cut) + mdLink + content.substring(cut)
            mdLinksInjected += 1
            changed = true
          }
        }

        if (changed) Files.write(htmlFile.toPath, content.getBytes(StandardCharsets.UTF_8))
      }
    }
    log.info(
      s"[llms] post-processed html: $linksInjected alternate links, $mdLinksInjected [md] links, $skipped skipped"
    )
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
