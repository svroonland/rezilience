import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtOnCompile
val mainScala     = "2.13.8"
val allScala      = Seq("2.12.15", mainScala)
val scala3Version = "3.1.2"

lazy val commonJvmSettings = Seq(
  crossScalaVersions := allScala :+ scala3Version
)

lazy val commonJsSettings = Seq(
  crossScalaVersions := allScala
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
    )
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(rezilience.js, rezilience.jvm, config, docs)
  .settings(
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
      "dev.zio"                %%% "zio-streams"             % "1.0.14",
      "dev.zio"                %%% "zio-test"                % "1.0.14" % "test",
      "dev.zio"                %%% "zio-test-sbt"            % "1.0.14" % "test",
      "org.scala-lang.modules" %%% "scala-collection-compat" % "2.8.0"
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
      "dev.zio"                %%% "zio-streams"             % "1.0.14",
      "dev.zio"                %%% "zio-config"              % "2.0.4",
      "dev.zio"                %%% "zio-config-typesafe"     % "2.0.4"  % "test",
      "dev.zio"                %%% "zio-test"                % "1.0.14" % "test",
      "dev.zio"                %%% "zio-test-sbt"            % "1.0.14" % "test",
      "org.scala-lang.modules" %%% "scala-collection-compat" % "2.8.0"
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
  .settings(
    name                                       := "rezilience",
    publish / skip                             := true,
    description                                := "ZIO-native utilities for making asynchronous systems more resilient to failures",
    ScalaUnidoc / siteSubdirName               := "api",
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
      "dev.zio"                %%% "zio-streams"             % "1.0.14",
      "dev.zio"                %%% "zio-test"                % "1.0.14" % "test",
      "dev.zio"                %%% "zio-test-sbt"            % "1.0.14" % "test",
      "org.scala-lang.modules" %%% "scala-collection-compat" % "2.8.0",
      "dev.zio"                %%% "zio-config-typesafe"     % "2.0.4"
    )
  )
  .dependsOn(rezilience.jvm, config)
