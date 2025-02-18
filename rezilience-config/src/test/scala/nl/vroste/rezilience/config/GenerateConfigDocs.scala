package nl.vroste.rezilience.config

import zio.{ Scope, ZIO, ZIOAppArgs }

object GenerateConfigDocs extends zio.ZIOAppDefault {
  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    ZIO
      .debug(s"""
                |# Circuit Breaker
                | ${zio.config.generateDocs(CircuitBreakerConfig.config).toTable.toGithubFlavouredMarkdown}
                |
                |# RateLimiter
                | ${zio.config.generateDocs(RateLimiterConfig.config).toTable.toGithubFlavouredMarkdown}
                |
                |# Bulkhead
                | ${zio.config.generateDocs(BulkheadConfig.config).toTable.toGithubFlavouredMarkdown}
                |
                |# Retry
                | ${zio.config.generateDocs(RetryConfig.config).toTable.toGithubFlavouredMarkdown}
                |
                |# Timeout
                | ${zio.config.generateDocs(TimeoutConfig.config).toTable.toGithubFlavouredMarkdown}
                |""".stripMargin)
      .exitCode

}
