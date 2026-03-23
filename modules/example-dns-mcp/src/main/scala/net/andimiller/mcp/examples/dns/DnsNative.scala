package net.andimiller.mcp.examples.dns

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait NativeMxRecord extends js.Object:
  val exchange: String = js.native
  val priority: Int    = js.native

@js.native
@JSImport("dns", JSImport.Default)
object DnsNative extends js.Object:

  /** Resolve A records for a hostname. */
  def resolve(
    hostname: String,
    callback: js.Function2[js.Error, js.Array[String], Unit]
  ): Unit = js.native

  /** Resolve records of a specific type (AAAA, MX, TXT, CNAME, NS, etc.). */
  def resolve(
    hostname: String,
    rrtype: String,
    callback: js.Function2[js.Error, js.Array[js.Any], Unit]
  ): Unit = js.native

  /** Reverse lookup: IP address -> hostnames. */
  def reverse(
    ip: String,
    callback: js.Function2[js.Error, js.Array[String], Unit]
  ): Unit = js.native
