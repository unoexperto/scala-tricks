import sbt.Keys._
import sbt._

lazy val commonSettings = Seq(
  name := "scala-tricks",
  organization := "com.walkmind",
  version := "2.42",

  licenses := Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0")),
  scalacOptions := Seq(
    "-unchecked",
    "-deprecation",
    "-encoding", "utf8",
    "-language:implicitConversions",
    "-language:postfixOps",
    "-language:higherKinds",
    "-Xcheckinit"),

  scalaVersion := "2.12.13",
  crossScalaVersions := Seq("2.12.13", "2.13.5")
)

lazy val publishSettings = {
  Seq(
    Test / publishArtifact := false,
    publishArtifact := true,

    scmInfo := Some(ScmInfo(url("https://github.com/unoexperto/scala-tricks.git"), "git@github.com:unoexperto/scala-tricks.git")),
    developers += Developer("unoexperto",
      "ruslan",
      "unoexperto.support@mailnull.com",
      url("https://github.com/unoexperto")),
    pomIncludeRepository := (_ => false),
    publishMavenStyle := true,
    publishTo := Some("Walkmind Repo" at "s3://walkmind-maven/")
  )
}

val catsVersion = "2.0.0-M4"

lazy val rootModels = (project in file(".")).
  settings(commonSettings: _*).
  settings(publishSettings: _*).
  settings(
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % Keys.scalaVersion.value,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.0.0",

      "commons-validator" % "commons-validator" % "1.6" % "provided" withSources(),
      "org.apache.commons" % "commons-lang3" % "3.9" % "provided" withSources(),
      "org.apache.james" % "apache-mime4j" % "0.8.3" % "provided",
      "com.google.guava" % "guava" % "28.0-jre" % "provided" withSources(),

      "io.spray" %% "spray-json" % "1.3.5" % "provided" withSources(),
      "org.scodec" %% "scodec-core" % "1.11.4" % "provided" withSources(),
      "org.scodec" %% "scodec-bits" % "1.1.12" % "provided" withSources(),

      "com.typesafe.akka" %% "akka-stream" % "2.6.0-M3" % "provided" withSources(),
      "com.typesafe.akka" %% "akka-http" % "10.1.8" % "provided" withSources(),

      "org.jsoup" % "jsoup" % "1.12.1" % "provided" withSources(),
      "org.asynchttpclient" % "async-http-client" % "2.10.0" % "provided" withSources(),

      "org.postgresql" % "postgresql" % "42.2.5" % "provided" withSources(),

      "org.typelevel" %% "cats-core" % catsVersion % "provided" withSources(),
      "org.typelevel" %% "cats-effect" % catsVersion % "provided" withSources(),
      "org.typelevel" %% "alleycats-core" % catsVersion withSources(),
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2" % "provided" withSources())
  )
  .enablePlugins(S3ResolverPlugin)
