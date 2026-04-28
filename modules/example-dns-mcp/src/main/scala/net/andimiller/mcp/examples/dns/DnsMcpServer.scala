package net.andimiller.mcp.examples.dns

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.{Dns as _, *}
import io.circe.Decoder
import io.circe.Encoder
import net.andimiller.mcp.core.protocol.PromptArgument
import net.andimiller.mcp.core.protocol.PromptMessage
import net.andimiller.mcp.core.schema.JsonSchema
import net.andimiller.mcp.core.schema.description
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.McpHttp

object DnsMcpServer extends IOApp.Simple:

  // ── request / response types ──────────────────────────────────────

  case class ResolveDnsRequest(
      @description("The hostname to resolve (e.g. example.com)")
      hostname: String,
      @description("DNS record type: A, AAAA, MX, TXT, CNAME, NS (default: A)")
      record_type: Option[String]
  ) derives JsonSchema,
        Decoder

  case class ReverseDnsRequest(
      @description("The IP address to reverse-lookup (e.g. 8.8.8.8)")
      ip: String
  ) derives JsonSchema,
        Decoder

  case class DnsResponse(records: List[String]) derives Encoder.AsObject, JsonSchema

  case class ErrorResponse(error: String) derives Encoder.AsObject, JsonSchema

  // ── server ────────────────────────────────────────────────────────

  private val dns = Dns.fromNodeJs

  final def run: IO[Unit] =
    McpHttp
      .streaming[IO]
      .name("dns-mcp")
      .version("1.0.0")
      .port(port"8053")
      .withExplorer(redirectToRoot = true)
      .withTools(
        tool
          .name("resolve_dns")
          .description("Resolve DNS records for a hostname. Supports A, AAAA, MX, TXT, CNAME, and NS record types.")
          .in[ResolveDnsRequest]
          .out[DnsResponse]
          .run { req =>
            val rrtype = req.record_type.map(_.toUpperCase).getOrElse("A")
            val lookup = rrtype match
              case "A"     => dns.resolveA(req.hostname)
              case "AAAA"  => dns.resolveAAAA(req.hostname)
              case "MX"    => dns.resolveMx(req.hostname).map(_.map((ex, pri) => s"$pri $ex"))
              case "TXT"   => dns.resolveTxt(req.hostname)
              case "CNAME" => dns.resolveCname(req.hostname)
              case "NS"    => dns.resolveNs(req.hostname)
              case other   => IO.raiseError(new Exception(s"Unsupported record type: $other"))
            lookup.map(DnsResponse(_))
          },
        tool
          .name("reverse_dns")
          .description("Perform a reverse DNS lookup for an IP address, returning associated hostnames.")
          .in[ReverseDnsRequest]
          .out[DnsResponse]
          .run(req => dns.reverse(req.ip).map(DnsResponse(_)))
      )
      .withResources(
        McpResource
          .static[IO](
            resourceUri = "dns://reference/record-types", resourceName = "DNS Record Types Reference",
            resourceDescription = Some("Quick reference for common DNS record types"),
            resourceMimeType = Some("text/markdown"),
            content = """|# DNS Record Types
                         |
                         || Type  | Description                        | Example                    |
                         ||-------|------------------------------------|----------------------------|
                         || A     | IPv4 address                       | 93.184.216.34              |
                         || AAAA  | IPv6 address                       | 2606:2800:220:1:248:...    |
                         || MX    | Mail exchange (priority + host)     | 10 mail.example.com        |
                         || TXT   | Arbitrary text (SPF, DKIM, etc.)   | v=spf1 include:...         |
                         || CNAME | Canonical name (alias)             | www -> example.com         |
                         || NS    | Authoritative nameserver           | ns1.example.com            |
                         |""".stripMargin
          )
          .resolve
      )
      .withPrompts(
        Prompt
          .static[IO](
            promptName = "diagnose_dns",
            promptDescription = Some("Comprehensive DNS diagnosis for a domain"),
            promptArguments = List(
              PromptArgument("domain", Some("The domain name to diagnose"), required = true)
            ),
            messages = List(
              PromptMessage.user(
                """|Please perform a comprehensive DNS diagnosis for the given domain.
                   |
                   |Use the resolve_dns tool to look up each record type (A, AAAA, MX, TXT, CNAME, NS)
                   |and then for each A record, use reverse_dns to check if reverse DNS is configured.
                   |
                   |Summarise findings including:
                   |1. IP addresses (v4 and v6)
                   |2. Mail configuration (MX records and SPF/DKIM in TXT)
                   |3. Nameservers
                   |4. Any CNAME aliases
                   |5. Reverse DNS consistency
                   |6. Potential issues or misconfigurations
                   |""".stripMargin
              )
            )
          )
          .resolve
      )
      .enableResourceSubscriptions
      .enableLogging
      .serve
      .useForever
