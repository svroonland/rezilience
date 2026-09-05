import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtOnCompile
import org.typelevel.scalacoptions.ScalaVersion.V3_0_0
import org.typelevel.scalacoptions.ScalacOption
import org.typelevel.scalacoptions.ScalacOptions
import scala.Ordering.Implicits._

import sbt.Keys.resolvers
val mainScala        = "3.9.0"
val scala3Version    = "3.3.8" // Keep at the latest Scala 3 LTS version
val allScala         = Seq(mainScala, scala3Version)
val zioVersion       = "2.1.26"
val zioConfigVersion = "4.0.8"

lazy val commonJvmSettings = Seq(
  crossScalaVersions := allScala,
  tpolecatScalacOptions ~= { options => options.filterNot(Set(ScalacOptions.lintInferAny)) },
  tpolecatScalacOptions ++= Set(
    ScalacOption("-Wconf", List("cat=scala3-migration:s"), _ < V3_0_0),
    ScalacOptions.source3
  )
)

lazy val commonJsSettings = Seq(
  crossScalaVersions := allScala,
  tpolecatScalacOptions ~= { options => options.filterNot(Set(ScalacOptions.lintInferAny)) },
  tpolecatScalacOptions ++= Set(
    ScalacOption("-Wconf", List("cat=scala3-migration:s"), _ < V3_0_0),
    ScalacOptions.source3
  )
)

inThisBuild(
  List(
    organization := "nl.vroste",
    scalaVersion := mainScala,
    homepage     := Some(uri("https://github.com/svroonland/rezilience")),
    licenses     := List("Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0")),
    developers   := List(
      Developer(
        "svroonland",
        "Vroste",
        "info@vroste.nl",
        uri("https://github.com/svroonland")
      )
    ),
    scmInfo      := Some(
      ScmInfo(uri("https://github.com/svroonland/rezilience/"), "scm:git:git@github.com:svroonland/rezilience.git")
    ),
    resolvers += Resolver.sonatypeCentralSnapshots,
    resolvers += Resolver.sonatypeCentralRepo("staging")
  )
)

lazy val root = project
  .in(file("."))
  .settings(commonJvmSettings)
  .aggregate(rezilience.js, rezilience.jvm, config, docs)
  .settings(
    name         := "rezilience-root",
    publish      := {},
    publishLocal := {}
  )

lazy val rezilience = crossProject(JSPlatform, JVMPlatform)
  .in(file("rezilience"))
  .jvmSettings(commonJvmSettings)
  .jsSettings(commonJsSettings)
  .settings(
    name                     := "rezilience",
    scalaVersion             := mainScala,
    Test / parallelExecution := false,
    Test / run / fork        := true,
    scalafmtOnCompile        := true,
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio-streams"             % zioVersion,
      "dev.zio"                %% "zio-test"                % zioVersion % "test",
      "dev.zio"                %% "zio-test-sbt"            % zioVersion % "test",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val config = project
  .in(file("rezilience-config"))
  .settings(commonJvmSettings)
  .settings(
    name                     := "rezilience-config",
    scalaVersion             := mainScala,
    Test / parallelExecution := false,
    Test / run / fork        := true,
    scalafmtOnCompile        := true,
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio-streams"             % zioVersion,
      "dev.zio"                %% "zio-config"              % zioConfigVersion,
      "dev.zio"                %% "zio-config-typesafe"     % zioConfigVersion % "test",
      "dev.zio"                %% "zio-test"                % zioVersion       % "test",
      "dev.zio"                %% "zio-test-sbt"            % zioVersion       % "test",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(rezilience.jvm)

addCommandAlias(
  "fmt",
  ";scalafmtSbt;scalafmt;rezilienceJVM/Test/scalafmt;config/Test/scalafmt"
)
addCommandAlias(
  "check",
  ";scalafmtSbtCheck;scalafmtCheck;rezilienceJVM/Test/scalafmtCheck;config/Test/scalafmtCheck"
)

lazy val docs = project
  .enablePlugins(ParadoxPlugin)
  .enablePlugins(SiteScaladocPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .enablePlugins(GhpagesPlugin)
  .settings(commonJvmSettings)
  .settings(
    scalaVersion                               := mainScala,
    name                                       := "rezilience-docs",
    publish / skip                             := true,
    description                                := "ZIO-native utilities for making asynchronous systems more resilient to failures",
    ScalaUnidoc / siteSubdirName               := "api",
    tpolecatScalacOptions ~= { options =>
      options.filterNot(Set(ScalacOptions.warnError, ScalacOptions.fatalWarnings))
    },
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, ScalaUnidoc / siteSubdirName),
    ScalaUnidoc / unidoc / unidocProjectFilter := inAnyProject -- inProjects(rezilience.js),
    Paradox / siteSubdirName                   := "docs",
    Compile / paradoxRoots                     := List(
      "index.html",
      "general_usage.html",
      "circuitbreaker.html",
      "bulkhead.html",
      "ratelimiter.html",
      "retry.html",
      "timeout.html",
      "combining_policies.html",
      "switching_policies.html",
      "zio-config.html",
      "additional_resiliency.html"
    ),
    git.remoteRepo                             := "git@github.com:svroonland/rezilience.git",
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio-streams"             % zioVersion,
      "dev.zio"                %% "zio-streams"             % zioVersion,
      "dev.zio"                %% "zio-test"                % zioVersion % "test",
      "dev.zio"                %% "zio-test-sbt"            % zioVersion % "test",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0",
      "dev.zio"                %% "zio-config-typesafe"     % "4.0.8"
    )
  )
  .dependsOn(rezilience.jvm, config)
