resolvers += "OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

addSbtPlugin("com.github.sbt"     % "sbt-protobuf"             % "0.8.3")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"             % "2.6.2")
addSbtPlugin("org.typelevel"      % "sbt-tpolecat"             % "0.5.7")
addSbtPlugin("com.eed3si9n"       % "sbt-assembly"             % "2.4.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.4.0")
addSbtPlugin("io.shiftleft"       % "sbt-ci-release-early"     % "2.1.15")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release"           % "1.12.1")
addSbtPlugin("com.github.sbt"     % "sbt-dynver"               % "5.1.1")
addSbtPlugin("com.github.sbt"     % "sbt-unidoc"               % "0.6.1")
addSbtPlugin("com.github.sbt"     % "sbt-site-paradox"         % "1.8.0")
addSbtPlugin("com.github.sbt"     % "sbt-ghpages"              % "0.10.0")

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
