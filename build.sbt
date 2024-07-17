import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtOnCompile
import org.typelevel.scalacoptions.ScalacOptions
import xerial.sbt.Sonatype.sonatypeCentralHost

ThisBuild / sonatypeCredentialHost := sonatypeCentralHost
ThisBuild / sonatypeRepository     := "https://s01.oss.sonatype.org/service/local"

import sbt.Keys.resolvers
val mainScala        = "2.13.14"
val scala3Version    = "3.3.3"
val allScala         = Seq(mainScala, scala3Version)
val zioVersion       = "2.1.6"
val zioConfigVersion = "4.0.2"

lazy val commonJvmSettings = Seq(
  crossScalaVersions := allScala,
  tpolecatScalacOptions ~= { options => options.filterNot(Set(ScalacOptions.lintInferAny)) }
)

lazy val commonJsSettings = Seq(
  crossScalaVersions := allScala,
  tpolecatScalacOptions ~= { options => options.filterNot(Set(ScalacOptions.lintInferAny)) }
)

inThisBuild(
  List(
    organization := "nl.vroste",
    homepage     := Some(url("https://github.com/svroonland/rezilience")),
    licenses     := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    developers   := List(
      Developer(
        "svroonland",
        "Vroste",
        "info@vroste.nl",
        url("https://github.com/svroonland")
      )
    ),
    scmInfo      := Some(
      ScmInfo(url("https://github.com/svroonland/rezilience/"), "scm:git:git@github.com:svroonland/rezilience.git")
    ),
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
  )
)

lazy val root = project
  .in(file("."))
  .settings(commonJvmSettings)
  .aggregate(rezilience.js, rezilience.jvm, config, docs)
  .settings(
    name         := "rezilience",
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
      "dev.zio"                %%% "zio-streams"             % zioVersion,
      "dev.zio"                %%% "zio-test"                % zioVersion % "test",
      "dev.zio"                %%% "zio-test-sbt"            % zioVersion % "test",
      "org.scala-lang.modules" %%% "scala-collection-compat" % "2.12.0"
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
      "dev.zio"                %%% "zio-streams"             % zioVersion,
      "dev.zio"                %%% "zio-config"              % zioConfigVersion,
      "dev.zio"                %%% "zio-config-typesafe"     % zioConfigVersion % "test",
      "dev.zio"                %%% "zio-test"                % zioVersion       % "test",
      "dev.zio"                %%% "zio-test-sbt"            % zioVersion       % "test",
      "org.scala-lang.modules" %%% "scala-collection-compat" % "2.12.0"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(rezilience.jvm)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val docs = project
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(SiteScaladocPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(commonJvmSettings)
  .settings(
    scalaVersion                               := mainScala,
    name                                       := "rezilience",
    publish / skip                             := true,
    description                                := "ZIO-native utilities for making asynchronous systems more resilient to failures",
    ScalaUnidoc / siteSubdirName               := "api",
    tpolecatScalacOptions ~= { options =>
      options.filterNot(Set(ScalacOptions.warnError, ScalacOptions.fatalWarnings))
    },
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, ScalaUnidoc / siteSubdirName),
    ScalaUnidoc / unidoc / unidocProjectFilter := inAnyProject -- inProjects(rezilience.js),
    git.remoteRepo                             := "git@github.com:svroonland/rezilience.git",
    micrositeUrl                               := "https://svroonland.github.io",
    micrositeBaseUrl                           := "/rezilience",
    micrositePushSiteWith                      := GitHub4s,
    micrositeGithubToken                       := sys.env.get("GITHUB_TOKEN"),
    micrositeHomepage                          := "https://svroonland.github.io/rezilience/",
    micrositeDocumentationUrl                  := "docs",
    micrositeAuthor                            := "vroste",
    micrositeTwitterCreator                    := "@vroste",
    micrositeGithubOwner                       := "svroonland",
    micrositeGithubRepo                        := "rezilience",
    micrositeGitterChannel                     := false,
    micrositeDataDirectory                     := file("docs/src/microsite/data"),
    micrositeFooterText                        := None,
    libraryDependencies ++= Seq(
      "dev.zio"                %%% "zio-streams"             % zioVersion,
      "dev.zio"                %%% "zio-test"                % zioVersion % "test",
      "dev.zio"                %%% "zio-test-sbt"            % zioVersion % "test",
      "org.scala-lang.modules" %%% "scala-collection-compat" % "2.12.0",
      "dev.zio"                %%% "zio-config-typesafe"     % "4.0.2"
    )
  )
  .dependsOn(rezilience.jvm, config)
