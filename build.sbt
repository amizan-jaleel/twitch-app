import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import scala.sys.process._

val scala3Version = "3.6.3"

ThisBuild / scalaVersion := scala3Version
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.twitch"
// Enforce new Scala 3 syntax (if...then, while...do) on backend and core.
// Frontend is excluded due to generated code (scalawind.scala).

// ── npm / Tailwind / Scalawind tasks ───────────────────────────────

val npmInstall = taskKey[Unit]("Run npm ci if node_modules is missing")
val scalawindGen = taskKey[Seq[File]]("Generate scalawind.scala from tailwind config")
val tailwindBuild = taskKey[File]("Build Tailwind CSS output")

val scalawindOutput = file("modules/frontend/src/main/scala/com/twitch/frontend/scalawind.scala")

// Helper to build command for Windows compatibility
def buildCommand(cmd: String, args: Seq[String]): Seq[String] =
  if (System.getProperty("os.name").toLowerCase.contains("win")) {
    Seq("cmd", "/c", cmd) ++ args
  } else {
    Seq(cmd) ++ args
  }

ThisBuild / npmInstall := {
  val log = streams.value.log
  val nodeModules = baseDirectory.value / "node_modules"
  if (!nodeModules.exists()) {
    log.info("Running npm ci...")
    val exitCode = Process(buildCommand("npm", Seq("ci")), baseDirectory.value)
      .!(ProcessLogger(s => log.info(s), s => log.error(s)))
    if (exitCode != 0) sys.error("npm ci failed")
  }
}

// ── Project modules ────────────────────────────────────────────────

lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.js, frontend, backend)
  .settings(
    publish := {},
    publishLocal := {},
  )

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(
    name := "core",
    scalacOptions ++= Seq("-new-syntax", "-no-indent"),
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % "3.5.7",
      "io.circe" %%% "circe-core" % "0.14.10",
      "io.circe" %%% "circe-generic" % "0.14.10",
      "io.circe" %%% "circe-parser" % "0.14.10",
    ),
  )

lazy val frontend = project
  .in(file("modules/frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(core.js)
  .settings(
    name := "frontend",
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("com.twitch.frontend.Main"),
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "calico" % "0.2.3",
      "org.http4s" %%% "http4s-dom" % "0.2.11",
      "org.http4s" %%% "http4s-circe" % "0.23.30",
    ),

    // Generate scalawind.scala before compilation
    scalawindGen := {
      val log = streams.value.log
      npmInstall.value
      if (!scalawindOutput.exists()) {
        log.info("Generating scalawind.scala...")
        val base = (ThisBuild / baseDirectory).value
        val exitCode = Process(
          buildCommand(
            "npx",
            Seq(
              "scalawind",
              "generate",
              "-o",
              scalawindOutput.absolutePath,
              "-p",
              "com.twitch.frontend",
            ),
          ),
          base,
        )
          .!(ProcessLogger(s => log.info(s), s => log.error(s)))
        if (exitCode != 0) sys.error("scalawind generate failed")
      }
      Seq(scalawindOutput)
    },
    // Build Tailwind CSS before compilation
    tailwindBuild := {
      val log = streams.value.log
      npmInstall.value
      val base = (ThisBuild / baseDirectory).value
      val outDir = base / "modules" / "frontend" / "dist"
      val outFile = outDir / "output.css"
      val inputFile = base / "modules" / "frontend" / "tailwind.css"
      if (!outDir.exists()) outDir.mkdirs()
      if (!outFile.exists() || inputFile.lastModified() > outFile.lastModified()) {
        log.info("Building Tailwind CSS...")
        val exitCode = Process(
          buildCommand(
            "npx",
            Seq("tailwindcss", "-i", inputFile.absolutePath, "-o", outFile.absolutePath),
          ),
          base,
        )
          .!(ProcessLogger(s => log.info(s), s => log.error(s)))
        if (exitCode != 0) sys.error("tailwindcss build failed")
      }
      outFile
    },
    Compile / compile := {
      scalawindGen.value
      tailwindBuild.value
      (Compile / compile).value
    },
  )

lazy val backend = project
  .in(file("modules/backend"))
  .dependsOn(core.jvm)
  .settings(
    name := "backend",
    scalacOptions ++= Seq("-new-syntax", "-no-indent"),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % "0.23.30",
      "org.http4s" %% "http4s-ember-client" % "0.23.30",
      "org.http4s" %% "http4s-dsl" % "0.23.30",
      "org.http4s" %% "http4s-circe" % "0.23.30",
      "ch.qos.logback" % "logback-classic" % "1.5.16",
      "org.tpolecat" %% "doobie-core" % "1.0.0-RC8",
      "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC8",
      "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC8",
      "org.postgresql" % "postgresql" % "42.7.4",
      "org.tpolecat" %% "doobie-h2" % "1.0.0-RC8",
      "com.h2database" % "h2" % "2.3.232",
      "com.typesafe" % "config" % "1.4.3",
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
    ),
    assembly / mainClass := Some("com.twitch.backend.TwitchServer"),
    assembly / assemblyJarName := "twitch-app.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case x => MergeStrategy.first
    },
  )

// ── Convenience command alias ──────────────────────────────────────

addCommandAlias("dev", ";frontend/fastLinkJS;backend/run")
