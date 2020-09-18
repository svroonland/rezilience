val mainScala = "2.13.3"
val allScala  = Seq("2.12.11", mainScala)

inThisBuild(
  List(
    organization := "nl.vroste",
    version := "0.2",
    homepage := Some(url("https://github.com/svroonland/rezilience")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion := mainScala,
    crossScalaVersions := allScala,
    parallelExecution in Test := false,
    fork in Test := false,
    fork in run := true,
    publishMavenStyle := true,
    publishArtifact in Test :=
      false,
    assemblyJarName in assembly := "rezilience-" + version.value + ".jar",
    test in assembly := {},
    target in assembly := file(baseDirectory.value + "/../bin/"),
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs @ _*)       => MergeStrategy.discard
      case n if n.startsWith("reference.conf") => MergeStrategy.concat
      case _                                   => MergeStrategy.first
    },
    bintrayOrganization := Some("vroste"),
    bintrayReleaseOnPublish in ThisBuild := false,
    bintrayPackageLabels := Seq("zio", "circuit-breaker")
  )
)

name := "rezilience"
scalafmtOnCompile := true

libraryDependencies ++= Seq(
  "dev.zio"                %% "zio-streams"             % "1.0.1",
  "dev.zio"                %% "zio-test"                % "1.0.1" % "test",
  "dev.zio"                %% "zio-test-sbt"            % "1.0.1" % "test",
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.2.0"
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
