package net.andimiller.mcp.examples.rag

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.{Files, Path}
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Location
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.GZip
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.{ExplorerRoutes, StreamableHttpTransport}
import fs2.io.compression.given

object RagMcpServer extends CommandIOApp(
  name   = "rag-mcp",
  header = "RAG MCP Server — semantic search over local files"
):

  private val pathsOpt = Opts.arguments[String]("paths").orEmpty
  private val extOpt   = Opts.options[String]("ext", "File extensions to include (repeatable)").orEmpty
  private val portOpt  = Opts.option[Int]("port", "HTTP port (default: 26000)").withDefault(26000)

  def main: Opts[IO[Nothing]] =
    (pathsOpt, extOpt, portOpt).mapN { (paths, exts, portNum) =>
      for
        logger     <- Slf4jLogger.create[IO]
        givenPort  <- IO.fromOption(Port.fromInt(portNum))(
                        new IllegalArgumentException(s"Invalid port: $portNum")
                      )
        cwd         = Path(System.getProperty("user.dir"))
        _          <- logger.info(s"Working directory: $cwd")
        _          <- logger.info("Loading ONNX embedding model...")
        result     <- EmbeddingService.resource.use { embeddings =>
          for
            _          <- logger.info("ONNX embedding model loaded")
            store      <- VectorStore.create(embeddings, cwd, logger)
            extensions  = if exts.isEmpty then None else Some(exts.toList)

            // Index startup paths
            indexedDirs <- paths.toList.traverse { pathStr =>
              val p = Path(pathStr)
              for
                validated <- store.validatePath(p)
                isDir     <- Files[IO].isDirectory(validated)
                result    <- {
                  if isDir then
                    store.indexDirectory(validated, extensions).flatMap { case (f, c) =>
                      logger.info(s"Startup: indexed $f files ($c chunks) from $validated")
                        .as(Some(validated))
                    }
                  else
                    store.indexFile(validated).flatMap { case (f, c) =>
                      logger.info(s"Startup: indexed $validated ($c chunks)")
                        .as(None: Option[Path])
                    }
                }
              yield result
            }

            // Start file watchers for indexed directories
            watchDirs = indexedDirs.flatten
            watchFiber <- {
              if watchDirs.nonEmpty then
                logger.info(s"Starting file watcher for ${watchDirs.size} directories") >>
                  store.watchDirectories(watchDirs).compile.drain.start.map(Some(_))
              else IO.pure(None)
            }

            // Build MCP server
            serverFactory = { (sink: NotificationSink[IO]) =>
              ServerBuilder[IO]("rag-mcp", "1.0.0")
                .withTools(
                  Tool.buildNamed[IO, IndexDirectoryRequest, IndexResponse](
                    "index_directory",
                    "Recursively index files in a directory for semantic search"
                  ) { req =>
                    val exts = req.extensions.filter(_.nonEmpty)
                    store.indexDirectory(Path(req.path), exts).map { case (files, chunks) =>
                      IndexResponse(s"Indexed $files files ($chunks chunks)", files, chunks)
                    }
                  },
                  Tool.buildNamed[IO, IndexFileRequest, IndexResponse](
                    "index_file",
                    "Index a single file for semantic search"
                  ) { req =>
                    store.indexFile(Path(req.path)).map { case (files, chunks) =>
                      IndexResponse(s"Indexed $files file ($chunks chunks)", files, chunks)
                    }
                  },
                  Tool.buildNamed[IO, SearchRequest, SearchResponse](
                    "search",
                    "Semantic search across indexed files"
                  ) { req =>
                    store.search(req.query, req.top_k.getOrElse(5)).map(SearchResponse(_))
                  },
                  Tool.buildNamed[IO, ListIndexedRequest, ListIndexedResponse](
                    "list_indexed",
                    "List all indexed files and statistics"
                  ) { _ =>
                    store.listIndexed
                  },
                  Tool.buildNamed[IO, ClearIndexRequest, MessageResponse](
                    "clear_index",
                    "Clear the entire search index"
                  ) { _ =>
                    store.clearIndex
                  }
                )
                .withResource(
                  McpResource.dynamic[IO](
                    resourceUri         = "rag://stats",
                    resourceName        = "RAG Index Statistics",
                    resourceDescription = Some("Current index statistics: file count, chunk count, languages, embedding dimensions"),
                    resourceMimeType    = Some("text/plain"),
                    reader              = () => store.statsText
                  )
                )
                .build
            }

            // Wire HTTP routes
            server <- StreamableHttpTransport.routes[IO](serverFactory).use { mcpRoutes =>
              val redirectRoute = HttpRoutes.of[IO] {
                case GET -> Root => IO.pure(Response[IO](Status.Found).withHeaders(Location(uri"/explorer/index.html")))
              }
              val routes = Router(
                "/" -> (redirectRoute <+> mcpRoutes),
                "/explorer" -> ExplorerRoutes[IO]
              )
              val app = GZip(routes).orNotFound

              EmberServerBuilder
                .default[IO]
                .withHost(host"0.0.0.0")
                .withPort(givenPort)
                .withHttpApp(app)
                .build
                .use { server =>
                  logger.info(s"RAG MCP server started on http://0.0.0.0:$portNum") >>
                    IO.never
                }
            }
          yield server
        }
      yield result
    }
