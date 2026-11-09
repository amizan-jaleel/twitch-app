addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")
addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.2")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

// These build-plugin transitives otherwise resolve to vulnerable legacy releases.
dependencyOverrides ++= Seq(
  "com.google.guava" % "guava" % "33.7.2-jre",
  "org.apache.commons" % "commons-lang3" % "3.18.0",
)
