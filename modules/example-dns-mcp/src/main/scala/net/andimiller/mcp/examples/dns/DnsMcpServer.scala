package net.andimiller.mcp.examples.dns

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.Port
import com.comcast.ip4s.port
import io.circe.{Decoder, Encoder}
import net.andimiller.mcp.core.protocol.{PromptArgument, PromptMessage}
import net.andimiller.mcp.core.schema.{JsonSchema, description}
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.http4s.HttpMcpApp

object DnsMcpServer extends HttpMcpApp[Dns[IO]]:

  def serverName    = "dns-mcp"
  def serverVersion = "1.0.0"
  override def port: Port = port"8053"

  def mkResources: Resource[IO, Dns[IO]] = Resource.pure(Dns.fromNodeJs)

  // ── request / response types ──────────────────────────────────────

  case class ResolveDnsRequest(
    @description("The hostname to resolve (e.g. example.com)")
    hostname: String,
    @description("DNS record type: A, AAAA, MX, TXT, CNAME, NS (default: A)")
    record_type: Option[String]
  ) derives JsonSchema, Decoder

  case class ReverseDnsRequest(
    @description("The IP address to reverse-lookup (e.g. 8.8.8.8)")
    ip: String
  ) derives JsonSchema, Decoder

  case class DnsResponse(records: List[String]) derives Encoder.AsObject, JsonSchema
  case class ErrorResponse(error: String) derives Encoder.AsObject, JsonSchema

  // ── tools ─────────────────────────────────────────────────────────

  override def tools(dns: Dns[IO], sink: NotificationSink[IO]): List[ToolHandler[IO]] = List(
    tool[ResolveDnsRequest, DnsResponse](
      "resolve_dns",
      "Resolve DNS records for a hostname. Supports A, AAAA, MX, TXT, CNAME, and NS record types."
    ) { req =>
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

    tool[ReverseDnsRequest, DnsResponse](
      "reverse_dns",
      "Perform a reverse DNS lookup for an IP address, returning associated hostnames."
    ) { req =>
      dns.reverse(req.ip).map(DnsResponse(_))
    }
  )

  // ── resources ─────────────────────────────────────────────────────

  override def resources(dns: Dns[IO], sink: NotificationSink[IO]): List[ResourceHandler[IO]] = List(
    McpResource.static[IO](
      resourceUri = "dns://reference/record-types",
      resourceName = "DNS Record Types Reference",
      resourceDescription = Some("Quick reference for common DNS record types"),
      resourceMimeType = Some("text/markdown"),
      content =
        """|# DNS Record Types
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
  )

  // ── prompts ───────────────────────────────────────────────────────

  override def prompts(dns: Dns[IO], sink: NotificationSink[IO]): List[PromptHandler[IO]] = List(
    Prompt.static[IO](
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
  )
