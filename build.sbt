import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val scala3Version = "3.6.3"

ThisBuild / scalaVersion := scala3Version
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.twitch"

lazy val root = project.in(file("."))
  .aggregate(core.jvm, core.js, frontend, backend)
  .settings(
    publish := {},
    publishLocal := {}
  )

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % "3.5.7",
      "io.circe"      %%% "circe-core"   % "0.14.10",
      "io.circe"      %%% "circe-generic" % "0.14.10",
      "io.circe"      %%% "circe-parser" % "0.14.10"
    )
  )

lazy val frontend = project.in(file("modules/frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(core.js)
  .settings(
    name := "frontend",
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("com.twitch.frontend.Main"),
    libraryDependencies ++= Seq(
      "io.indigoengine" %%% "tyrian" % "0.11.0"
    )
  )

lazy val backend = project.in(file("modules/backend"))
  .dependsOn(core.jvm)
  .settings(
    name := "backend",
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-server" % "0.23.30",
      "org.http4s"    %% "http4s-ember-client" % "0.23.30",
      "org.http4s"    %% "http4s-dsl"          % "0.23.30",
      "org.http4s"    %% "http4s-circe"        % "0.23.30",
      "ch.qos.logback" % "logback-classic"     % "1.5.16",
      "org.tpolecat"  %% "doobie-core"         % "1.0.0-RC8",
      "org.tpolecat"  %% "doobie-h2"           % "1.0.0-RC8",
      "org.tpolecat"  %% "doobie-hikari"       % "1.0.0-RC8",
      "com.h2database" % "h2"                  % "2.3.232"
    )
  )
