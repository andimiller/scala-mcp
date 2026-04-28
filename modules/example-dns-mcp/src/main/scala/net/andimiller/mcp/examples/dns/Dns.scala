package net.andimiller.mcp.examples.dns

import cats.effect.IO

import scala.scalajs.js

/** Pure functional interface to DNS resolution, backed by Node.js `dns` module. */
trait Dns[F[_]]:

  def resolveA(hostname: String): F[List[String]]

  def resolveAAAA(hostname: String): F[List[String]]

  def resolveMx(hostname: String): F[List[(String, Int)]]

  def resolveTxt(hostname: String): F[List[String]]

  def resolveCname(hostname: String): F[List[String]]

  def resolveNs(hostname: String): F[List[String]]

  def reverse(ip: String): F[List[String]]

object Dns:

  /** Create a `Dns[IO]` that delegates to Node.js `dns.resolve` / `dns.reverse`. */
  def fromNodeJs: Dns[IO] = new Dns[IO]:

    def resolveA(hostname: String): IO[List[String]] =
      IO.async_[List[String]] { cb =>
        DnsNative.resolve(
          hostname,
          (err, result) =>
            if err != null then cb(Left(new Exception(err.message)))
            else cb(Right(result.toList))
        )
      }

    def resolveAAAA(hostname: String): IO[List[String]] =
      resolveStringRecords(hostname, "AAAA")

    def resolveMx(hostname: String): IO[List[(String, Int)]] =
      IO.async_[List[(String, Int)]] { cb =>
        DnsNative.resolve(
          hostname,
          "MX",
          (err, result) =>
            if err != null then cb(Left(new Exception(err.message)))
            else
              val records = result.toList.map { r =>
                val mx = r.asInstanceOf[NativeMxRecord]
                (mx.exchange, mx.priority)
              }
              cb(Right(records))
        )
      }

    def resolveTxt(hostname: String): IO[List[String]] =
      IO.async_[List[String]] { cb =>
        DnsNative.resolve(
          hostname,
          "TXT",
          (err, result) =>
            if err != null then cb(Left(new Exception(err.message)))
            else
              // TXT records come back as Array[Array[String]], we flatten and join chunks
              val records = result.toList.map { r =>
                r.asInstanceOf[js.Array[String]].toList.mkString
              }
              cb(Right(records))
        )
      }

    def resolveCname(hostname: String): IO[List[String]] =
      resolveStringRecords(hostname, "CNAME")

    def resolveNs(hostname: String): IO[List[String]] =
      resolveStringRecords(hostname, "NS")

    def reverse(ip: String): IO[List[String]] =
      IO.async_[List[String]] { cb =>
        DnsNative.reverse(
          ip,
          (err, result) =>
            if err != null then cb(Left(new Exception(err.message)))
            else cb(Right(result.toList))
        )
      }

    private def resolveStringRecords(hostname: String, rrtype: String): IO[List[String]] =
      IO.async_[List[String]] { cb =>
        DnsNative.resolve(
          hostname,
          rrtype,
          (err, result) =>
            if err != null then cb(Left(new Exception(err.message)))
            else cb(Right(result.toList.map(_.asInstanceOf[String])))
        )
      }
