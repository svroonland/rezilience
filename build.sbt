import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtOnCompile
val mainScala = "2.13.3"
val allScala  = Seq("2.12.12", mainScala)

lazy val root = project
  .in(file("."))
  .aggregate(rezilience.js, rezilience.jvm)
  .settings(
    publish := {},
    publishLocal := {}
  )

lazy val rezilience = crossProject(JSPlatform, JVMPlatform)
  .in(file("rezilience"))
  .enablePlugins(GitVersioning)
  .settings(
    name := "rezilience",
    organization := "nl.vroste",
    homepage := Some(url("https://github.com/svroonland/rezilience")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion := mainScala,
    crossScalaVersions := allScala,
    parallelExecution in Test := false,
    fork in Test := false,
    fork in run := true,
    publishMavenStyle := true,
    bintrayOrganization := Some("vroste"),
    bintrayPackageLabels := Seq("zio", "circuit-breaker"),
    scalafmtOnCompile := true,
    libraryDependencies ++= Seq(
      "dev.zio"                %%% "zio-streams"             % "1.0.1",
      "dev.zio"                %%% "zio-test"                % "1.0.1" % "test",
      "dev.zio"                %%% "zio-test-sbt"            % "1.0.1" % "test",
      "org.scala-lang.modules" %%% "scala-collection-compat" % "2.2.0"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val docs = project
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(SiteScaladocPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    name := "rezilience",
    description := "ZIO-native utilities for making asynchronous systems more resilient to failures",
//    publishArtifact := false,
    siteSubdirName in ScalaUnidoc := "api",
    addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), siteSubdirName in ScalaUnidoc),
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(rezilience.js),
//    git.remoteRepo := "git@github.com:vigoo/desert.git",
//    micrositeUrl := "https://svroonland.github.io",
//    micrositeBaseUrl := "/rezilience",
    micrositeHomepage := "https://svroonland.github.io/rezilience/",
    micrositeDocumentationUrl := "docs",
    micrositeAuthor := "vroste",
    micrositeTwitterCreator := "@vroste",
    micrositeGithubOwner := "svroonland",
    micrositeGithubRepo := "rezilience",
    micrositeGitterChannel := false,
    micrositeDataDirectory := file("docs/src/microsite/data")
//    micrositeStaticDirectory := file("docs/src/microsite/static"),
//    micrositeImgDirectory := file("docs/src/microsite/img"),
//    micrositeCssDirectory := file("docs/src/microsite/styles"),
//    micrositeSassDirectory := file("docs/src/microsite/partials"),
//    micrositeJsDirectory := file("docs/src/microsite/scripts"),
//    micrositeTheme := "light",
//    micrositeHighlightLanguages ++= Seq("scala", "sbt"),
//    micrositeConfigYaml := ConfigYml(
//      yamlCustomProperties = Map(
//        "url" -> "https://vigoo.github.io",
//        "plugins" -> List("jemoji", "jekyll-sitemap")
//      )
//    ),
//    micrositeFooterText := Some("<a href='https://thenounproject.com/search/?q=Evolution%20&i=2373364'>Evolution</a> by Nithinan Tatah from the Noun Project<br><a href='https://thenounproject.com/search/?q=floppy&i=303328'>Floppy</a> by Jonathan Li from the Noun Project"),
//    micrositeAnalyticsToken := "UA-56320875-2",
//    includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf" | "*.txt" | "*.xml" | "*.svg",
  )
  .dependsOn(rezilience.jvm)
