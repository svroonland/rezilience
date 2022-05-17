package nl.vroste.rezilience.config

import zio.{ ExitCode, URIO, ZIO }

object GenerateConfigDocs extends zio.App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    ZIO.debug(zio.config.generateDocs(CircuitBreakerConfig.descriptor).toTable.toGithubFlavouredMarkdown).exitCode

}
