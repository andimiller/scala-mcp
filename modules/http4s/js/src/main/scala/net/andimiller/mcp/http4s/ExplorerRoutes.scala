package net.andimiller.mcp.http4s

import cats.effect.kernel.Async
import cats.syntax.all.*
import fs2.io.file.{Files, Path as FsPath}
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object ExplorerRoutes:
  def apply[F[_]: Async: Files]: HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*
    HttpRoutes.of[F] {
      case GET -> path =>
        val filePath = FsPath("./explorer") / path.segments.mkString("/")
        Files[F].exists(filePath).flatMap {
          case true =>
            val body = Files[F].readAll(filePath)
            val ct = contentType(filePath.toString)
            Ok(body).map(_.withContentType(ct))
          case false => NotFound()
        }
    }

  private def contentType(path: String): `Content-Type` =
    if path.endsWith(".html") then `Content-Type`(MediaType.text.html)
    else if path.endsWith(".js") then `Content-Type`(MediaType.application.javascript)
    else if path.endsWith(".css") then `Content-Type`(MediaType.text.css)
    else if path.endsWith(".json") || path.endsWith(".map") then `Content-Type`(MediaType.application.json)
    else `Content-Type`(MediaType.application.`octet-stream`)
