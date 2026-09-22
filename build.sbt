ThisBuild / scalaVersion := "3.3.6" // Scala 3 LTS
ThisBuild / organization := "com.example"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "program-analysis-generator",
    Compile / mainClass := Some("pag.Main"),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.1.1" % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-source:3.3",
      "-Wunused:all"
    ),
    javacOptions ++= Seq("-source", "21", "-target", "21"),
    run / fork := true,
    Test / fork := true
  )
