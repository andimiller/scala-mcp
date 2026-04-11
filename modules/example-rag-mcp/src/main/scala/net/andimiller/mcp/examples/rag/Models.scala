package net.andimiller.mcp.examples.rag

import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.schema.{JsonSchema, description}

// ── Index state ──────────────────────────────────────────────────────────

case class Chunk(
  id: Int,
  filePath: String,
  startLine: Int,
  endLine: Int,
  language: String,
  text: String,
  embedding: Array[Float]
)

case class IndexedFile(
  path: String,
  language: String,
  chunkCount: Int
)

case class IndexState(
  files: Map[String, IndexedFile],
  chunks: Vector[Chunk],
  nextId: Int
):
  def replaceFile(path: String, language: String, newChunks: Vector[Chunk]): IndexState =
    val withoutOld = chunks.filterNot(_.filePath == path)
    val numbered   = newChunks.zipWithIndex.map { (c, i) => c.copy(id = nextId + i) }
    IndexState(
      files   = files.updated(path, IndexedFile(path, language, numbered.size)),
      chunks  = withoutOld ++ numbered,
      nextId  = nextId + numbered.size
    )

  def clear: IndexState = IndexState(Map.empty, Vector.empty, 0)

object IndexState:
  val empty: IndexState = IndexState(Map.empty, Vector.empty, 0)

// ── Tool requests ────────────────────────────────────────────────────────

case class IndexDirectoryRequest(
  @description("Directory path to index (must be within working directory)")
  path: String,
  @description("File extensions to include (e.g. scala, py). If empty, all recognized extensions are included.")
  extensions: Option[List[String]] = None
) derives JsonSchema, Decoder

case class IndexFileRequest(
  @description("File path to index (must be within working directory)")
  path: String
) derives JsonSchema, Decoder

case class SearchRequest(
  @description("Natural language search query")
  query: String,
  @description("Maximum number of results to return (default: 5)")
  top_k: Option[Int] = None
) derives JsonSchema, Decoder

case class ListIndexedRequest() derives JsonSchema, Decoder

case class ClearIndexRequest() derives JsonSchema, Decoder

// ── Tool responses ───────────────────────────────────────────────────────

case class IndexResponse(
  message: String,
  files_indexed: Int,
  chunks_created: Int
) derives Encoder.AsObject, JsonSchema

case class SearchResult(
  file_path: String,
  start_line: Int,
  end_line: Int,
  language: String,
  score: Double,
  text: String
) derives Encoder.AsObject, JsonSchema

case class SearchResponse(
  results: List[SearchResult]
) derives Encoder.AsObject, JsonSchema

case class IndexedFileInfo(
  path: String,
  language: String,
  chunk_count: Int
) derives Encoder.AsObject, JsonSchema

case class IndexStats(
  total_files: Int,
  total_chunks: Int,
  languages: List[String]
) derives Encoder.AsObject, JsonSchema

case class ListIndexedResponse(
  files: List[IndexedFileInfo],
  stats: IndexStats
) derives Encoder.AsObject, JsonSchema

case class MessageResponse(
  message: String
) derives Encoder.AsObject, JsonSchema
