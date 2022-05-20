package nl.vroste.rezilience.config

import zio.{ ExitCode, URIO, ZIO }

object GenerateConfigDocs extends zio.App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
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
