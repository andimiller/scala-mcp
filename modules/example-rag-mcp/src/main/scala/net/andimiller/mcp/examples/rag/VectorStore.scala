package net.andimiller.mcp.examples.rag

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import org.typelevel.log4cats.Logger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class VectorStore(
  state: Ref[IO, IndexState],
  embeddings: EmbeddingService,
  cwd: Path,
  logger: Logger[IO]
):

  def validatePath(p: Path): IO[Path] =
    val abs = p.toNioPath.toAbsolutePath.normalize
    val cwdAbs = cwd.toNioPath.toAbsolutePath.normalize
    if abs.startsWith(cwdAbs) then IO.pure(Path.fromNioPath(abs))
    else IO.raiseError(new IllegalArgumentException(
      s"Path $p is outside the working directory ($cwd)"
    ))

  def indexFile(path: Path): IO[(Int, Int)] =
    for
      validated <- validatePath(path)
      exists    <- Files[IO].exists(validated)
      _         <- IO.raiseWhen(!exists)(new IllegalArgumentException(s"File not found: $validated"))
      size      <- Files[IO].size(validated)
      _         <- IO.raiseWhen(size > Chunker.maxFileSizeBytes)(
                     new IllegalArgumentException(s"File too large (${size} bytes, max ${Chunker.maxFileSizeBytes}): $validated")
                   )
      language  <- IO.fromOption(Chunker.detectLanguage(validated))(
                     new IllegalArgumentException(s"Unrecognized file type: $validated")
                   )
      content   <- Files[IO].readUtf8(validated).compile.string
      chunkData  = Chunker.chunk(content)
      _         <- logger.info(s"Indexing file: $validated (${chunkData.size} chunks)")
      texts      = chunkData.map(_._3).toList
      vectors   <- if texts.nonEmpty then embeddings.embedAll(texts) else IO.pure(List.empty)
      newChunks  = chunkData.zip(vectors).map { case ((startLine, endLine, text), vec) =>
                     Chunk(
                       id        = 0, // assigned by replaceFile
                       filePath  = validated.toString,
                       startLine = startLine,
                       endLine   = endLine,
                       language  = language,
                       text      = text,
                       embedding = vec
                     )
                   }
      _         <- state.update(_.replaceFile(validated.toString, language, newChunks))
    yield (1, newChunks.size)

  def indexDirectory(dir: Path, extensions: Option[List[String]]): IO[(Int, Int)] =
    for
      validated <- validatePath(dir)
      isDir     <- Files[IO].isDirectory(validated)
      _         <- IO.raiseWhen(!isDir)(new IllegalArgumentException(s"Not a directory: $validated"))
      files     <- Files[IO].walk(validated)
                     .filter(p => !p.fileName.toString.startsWith("."))
                     .filter(p => !p.toNioPath.iterator().asScala.exists(seg => seg.toString == "node_modules" || seg.toString == "target" || seg.toString == ".git"))
                     .evalFilter(p => Files[IO].isRegularFile(p))
                     .filter(Chunker.isIndexable)
                     .filter(p => Chunker.isExtensionAllowed(p, extensions))
                     .evalFilter(p => Files[IO].size(p).map(_ <= Chunker.maxFileSizeBytes))
                     .compile.toList
      _         <- logger.info(s"Indexing directory: $validated (${files.size} files found)")
      results   <- files.traverse(indexFile)
    yield results.foldLeft((0, 0)) { case ((af, ac), (f, c)) => (af + f, ac + c) }

  def search(query: String, topK: Int): IO[List[SearchResult]] =
    for
      queryVec <- embeddings.embed(query)
      st       <- state.get
      scored    = st.chunks.map { chunk =>
                    val score = cosineSimilarity(queryVec, chunk.embedding)
                    SearchResult(
                      file_path  = chunk.filePath,
                      start_line = chunk.startLine,
                      end_line   = chunk.endLine,
                      language   = chunk.language,
                      score      = BigDecimal(score).setScale(4, BigDecimal.RoundingMode.HALF_UP).toDouble,
                      text       = chunk.text
                    )
                  }
      results   = scored.sortBy(-_.score).take(topK).toList
      _        <- logger.info(s"Search: '$query' returned ${results.size} results")
    yield results

  def listIndexed: IO[ListIndexedResponse] =
    state.get.map { st =>
      val files = st.files.values.toList.sortBy(_.path).map { f =>
        IndexedFileInfo(f.path, f.language, f.chunkCount)
      }
      val languages = st.files.values.map(_.language).toSet.toList.sorted
      ListIndexedResponse(
        files = files,
        stats = IndexStats(
          total_files  = st.files.size,
          total_chunks = st.chunks.size,
          languages    = languages
        )
      )
    }

  def clearIndex: IO[MessageResponse] =
    state.set(IndexState.empty).as(MessageResponse("Index cleared"))

  def statsText: IO[String] =
    state.get.map { st =>
      val languages = st.files.values.map(_.language).toSet.toList.sorted
      s"""Files indexed: ${st.files.size}
         |Chunks: ${st.chunks.size}
         |Languages: ${languages.mkString(", ")}
         |Embedding dimensions: ${embeddings.dimensions}""".stripMargin
    }

  def watchDirectories(dirs: List[Path]): Stream[IO, Unit] =
    Stream.emits(dirs).map { dir =>
      Files[IO].watch(dir)
        .filter { event =>
          event.isInstanceOf[fs2.io.file.Watcher.Event.Created] ||
          event.isInstanceOf[fs2.io.file.Watcher.Event.Modified]
        }
        .collect {
          case e: fs2.io.file.Watcher.Event.Created  => e.path
          case e: fs2.io.file.Watcher.Event.Modified => e.path
        }
        .filter(p => Chunker.isIndexable(p))
        .debounce(500.millis)
        .evalMap { path =>
          logger.info(s"Reindexing $path because contents changed") >>
            indexFile(path).void.handleErrorWith { err =>
              logger.warn(s"Failed to reindex $path: ${err.getMessage}")
            }
        }
    }.parJoinUnbounded

  private def cosineSimilarity(a: Array[Float], b: Array[Float]): Double =
    var dot  = 0.0
    var normA = 0.0
    var normB = 0.0
    var i = 0
    while i < a.length do
      dot   += a(i).toDouble * b(i).toDouble
      normA += a(i).toDouble * a(i).toDouble
      normB += b(i).toDouble * b(i).toDouble
      i += 1
    if normA == 0.0 || normB == 0.0 then 0.0
    else dot / (math.sqrt(normA) * math.sqrt(normB))

object VectorStore:
  def create(embeddings: EmbeddingService, cwd: Path, logger: Logger[IO]): IO[VectorStore] =
    Ref.of[IO, IndexState](IndexState.empty).map { state =>
      new VectorStore(state, embeddings, cwd, logger)
    }
