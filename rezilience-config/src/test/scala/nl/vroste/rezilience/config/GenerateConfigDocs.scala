package nl.vroste.rezilience.config

import zio.{ Scope, ZIO, ZIOAppArgs }

object GenerateConfigDocs extends zio.ZIOAppDefault {
  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    ZIO
      .debug(s"""
                |# Circuit Breaker
                | ${zio.config.generateDocs(CircuitBreakerConfig.descriptor).toTable.toGithubFlavouredMarkdown}
                | 
                |# RateLimiter
                | ${zio.config.generateDocs(RateLimiterConfig.descriptor).toTable.toGithubFlavouredMarkdown}
                |       
                |# Bulkhead
                | ${zio.config.generateDocs(BulkheadConfig.descriptor).toTable.toGithubFlavouredMarkdown}
                |        
                |# Retry
                | ${zio.config.generateDocs(RetryConfig.descriptor).toTable.toGithubFlavouredMarkdown}
                | 
                |# Timeout
                | ${zio.config.generateDocs(TimeoutConfig.descriptor).toTable.toGithubFlavouredMarkdown}
                |""".stripMargin)
      .exitCode

}
