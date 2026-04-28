package net.andimiller.mcp.openapi

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import cats.effect.IO

import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.parse as parseJson

object McpJsonFile:

  /** Parse config file or return empty structure. */
  def read(fmt: ConfigFormat): IO[(Json, JsonObject)] =
    IO {
      val path = fmt.filePath
      val json =
        if Files.exists(path) then
          parseJson(new String(Files.readAllBytes(path), "UTF-8"))
            .getOrElse(fmt.emptyFile)
        else fmt.emptyFile
      val servers = json.hcursor.downField(fmt.serversKey).as[JsonObject].getOrElse(JsonObject.empty)
      (json, servers)
    }

  /** Write pretty-printed JSON to config file. */
  def write(path: Path, json: Json): IO[Unit] =
    IO(Files.write(path, json.spaces2.getBytes("UTF-8"))).void

  /** List all server entries as (name, json) pairs. */
  def listEntries(fmt: ConfigFormat): IO[List[(String, Json)]] =
    read(fmt).map { case (_, servers) =>
      servers.toList
    }

  /** Upsert a server entry via deepMerge. */
  def addEntry(fmt: ConfigFormat, serverName: String, specSource: String, operationIds: List[String]): IO[Unit] =
    read(fmt).flatMap { case (existing, _) =>
      val entry   = fmt.mkEntry(specSource, operationIds)
      val updated = existing.deepMerge(
        Json.obj(fmt.serversKey -> Json.obj(serverName -> entry))
      )
      write(fmt.filePath, updated)
    }

  /** Remove a server entry by name. Returns true if found and removed. */
  def deleteEntry(fmt: ConfigFormat, serverName: String): IO[Boolean] =
    read(fmt).flatMap { case (existing, servers) =>
      if servers.contains(serverName) then
        val key     = fmt.serversKey
        val updated = existing.mapObject { obj =>
          obj(key).flatMap(_.asObject) match
            case Some(serversObj) =>
              obj.add(key, Json.fromJsonObject(serversObj.remove(serverName)))
            case None => obj
        }
        write(fmt.filePath, updated).as(true)
      else IO.pure(false)
    }

  /** Derive a server name, preferring the OpenAPI spec title when available. */
  def deriveServerName(specSource: String, specTitle: Option[String] = None): String =
    val raw = specTitle.map(normaliseTitle).filter(_.nonEmpty).getOrElse {
      if specSource.startsWith("http") then scala.util.Try(new java.net.URI(specSource).getHost).getOrElse(specSource)
      else
        val name = Paths.get(specSource).getFileName.toString
        val dot  = name.lastIndexOf('.')
        if dot > 0 then name.substring(0, dot) else name
    }
    s"openapi-$raw"

  private def normaliseTitle(title: String): String =
    title.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .replaceAll("-+", "-")
      .stripPrefix("-")
      .stripSuffix("-")
