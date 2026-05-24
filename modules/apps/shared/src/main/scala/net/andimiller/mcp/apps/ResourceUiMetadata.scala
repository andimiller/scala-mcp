package net.andimiller.mcp.apps

import io.circe.Decoder
import io.circe.Encoder

/** Content Security Policy fragments the host should apply when rendering the app iframe. Each list is an allowlist of
  * origins or sources (e.g. `"https://api.example.com"`, `"self"`). All fields default to `None` meaning "no host-side
  * additions beyond the host's own defaults".
  */
case class CspPolicy(
    connect: Option[List[String]] = None,
    resource: Option[List[String]] = None,
    frame: Option[List[String]] = None,
    baseUri: Option[List[String]] = None
) derives Encoder.AsObject,
      Decoder

/** Browser permissions the iframe needs. Only the spec-enumerated values are valid. */
enum AppPermission derives CanEqual:
  case Camera
  case Microphone
  case Geolocation
  case ClipboardWrite

object AppPermission:

  given Encoder[AppPermission] = Encoder[String].contramap {
    case Camera         => "camera"
    case Microphone     => "microphone"
    case Geolocation    => "geolocation"
    case ClipboardWrite => "clipboard-write"
  }

  given Decoder[AppPermission] = Decoder[String].emap {
    case "camera"          => Right(Camera)
    case "microphone"      => Right(Microphone)
    case "geolocation"     => Right(Geolocation)
    case "clipboard-write" => Right(ClipboardWrite)
    case other             => Left(s"Unknown app permission: $other")
  }

/** `_meta.io.modelcontextprotocol/ui` payload for an app resource (a `ui://` HTML resource the host renders in an
  * iframe).
  *
  *   - `csp` — additional CSP fragments to layer onto the host's defaults.
  *   - `permissions` — browser permissions the iframe needs (camera, microphone, etc.).
  *   - `domain` — request a dedicated sandbox origin (host may ignore).
  *   - `prefersBorder` — hint that the host should render a visible border around the iframe.
  */
case class ResourceUiMetadata(
    csp: Option[CspPolicy] = None,
    permissions: Option[List[AppPermission]] = None,
    domain: Option[String] = None,
    prefersBorder: Option[Boolean] = None
) derives Encoder.AsObject,
      Decoder
