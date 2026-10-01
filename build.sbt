ThisBuild / scalaVersion := "3.3.6" // Scala 3 LTS
ThisBuild / organization := "com.example"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Soot 4.7.1 depends on com.android.tools.smali:smali-dexlib2, which is not on
// Maven Central. Soot's own POM declares this repository for it; sbt does not
// follow repositories declared in a dependency's POM, so it is repeated here.
ThisBuild / resolvers += "google" at "https://maven.google.com/"

// Module layout and boundaries: implementation_strategy.md §3 and §5.5.

/** Fails if a Soot jar is on this module's compile classpath. Only
  * frontend-soot may compile against soot.* (§5.5); `test` runs this first.
  */
lazy val checkNoSootOnCompileClasspath = taskKey[Unit]("fail if soot is a compile dependency")

/** Fails if the Scala library is on this module's compile classpath. api and
  * probe-lib are pure Java (§3); `test` runs this first.
  */
lazy val checkPureJava = taskKey[Unit]("fail if scala-library is a compile dependency")

def jarsNamed(cp: Classpath, prefix: String): Seq[String] =
  cp.map(_.data.getName).filter(_.startsWith(prefix))

lazy val sootBoundary = Seq(
  checkNoSootOnCompileClasspath := {
    val found = jarsNamed((Compile / dependencyClasspath).value, "soot-")
    if (found.nonEmpty)
      sys.error(s"${name.value}: soot on the compile classpath (${found.mkString(", ")}); " +
        "only frontend-soot may depend on it at compile time")
  },
  Test / test := (Test / test).dependsOn(checkNoSootOnCompileClasspath).value
)

lazy val pureJava = sootBoundary ++ Seq(
  crossPaths       := false,
  autoScalaLibrary := false,
  javacOptions ++= Seq("-source", "21", "-target", "21"),
  libraryDependencies += "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
  // Name each JUnit test in `sbt test` output, as MUnit does for the Scala modules.
  Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-v"),
  checkPureJava := {
    val found = jarsNamed((Compile / dependencyClasspath).value, "scala")
    if (found.nonEmpty)
      sys.error(s"${name.value}: must be pure Java, found ${found.mkString(", ")}")
  },
  Test / test := (Test / test).dependsOn(checkPureJava).value
)

lazy val scalaModule = Seq(
  libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test,
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-source:3.3",
    "-Wunused:all",
    // A match missing a case of a sealed type fails the build instead of warning:
    // the engine's trust-base code relies on it (implementation_strategy.md §2).
    "-Wconf:id=E029:e"
  ),
  Test / fork := true
)

lazy val root = (project in file("."))
  .aggregate(api, probeLib, ir, frontendSoot, core, harness, cli)
  .settings(
    name           := "program-analysis-generator",
    publish / skip := true
  )

/** The domain contract and vocabulary (§5.4). What generated domains compile against. */
lazy val api = (project in file("engine/api"))
  .settings(pureJava, name := "pag-api")

/** Rand and Reach, the only library a probe may call (§5.6, §5.8). */
lazy val probeLib = (project in file("engine/probe-lib"))
  .settings(
    pureJava,
    name := "pag-probe-lib",
    // ProbeRunTest starts real JVMs on the test JVM's own classpath, which is
    // sbt's launcher classpath unless the tests are forked.
    Test / fork := true
  )

/** The IR in Scala: source IR, Step, Cfg (§5.1, §5.3). Never seen by a domain. */
lazy val ir = (project in file("engine/ir"))
  .settings(scalaModule, sootBoundary, name := "pag-ir")

/** The only module with Soot on its compile classpath (§5.5). */
lazy val frontendSoot = (project in file("engine/frontend-soot"))
  // probe-lib for tests only: fixtures are compiled against Rand and Reach.
  .dependsOn(ir, probeLib % "test->compile")
  .settings(
    scalaModule,
    name := "pag-frontend-soot",
    libraryDependencies ++= Seq(
      "org.soot-oss" % "soot" % "4.7.1",
      // Soot logs through SLF4J 2.0.17; without a backend every run prints three
      // "No SLF4J providers" warnings. The no-op backend silences Soot's logging.
      "org.slf4j" % "slf4j-nop" % "2.0.17" % Runtime
    )
  )

/** Profile check, lifting, lowering, worklist, certifier. */
lazy val core = (project in file("engine/core"))
  .dependsOn(api, ir)
  .settings(scalaModule, sootBoundary, name := "pag-core")

/** Executor, probe runner, verdicts, scoring. */
lazy val harness = (project in file("engine/harness"))
  // probe-lib: the IR interpreter answers randInt with the same Inputs parser Rand
  // uses (§5.6), and the JVM run puts probe-lib on the probe's classpath. Its test
  // classes: JvmRunSuite runs probe-lib's compiled fixtures as probes.
  .dependsOn(core, probeLib % "compile->compile;test->test")
  .settings(scalaModule, sootBoundary, name := "pag-harness")

/** The `pag` entry point. Sees frontend-soot at runtime only, through
  * ServiceLoader, so neither soot.* nor SootIrProvider is visible to it at
  * compile time (§5.5).
  */
lazy val cli = (project in file("engine/cli"))
  // test->test: cli's tests reuse frontend-soot's fixtures and their compiler.
  // That puts Soot on cli's *test* classpath; its main code still cannot see it.
  .dependsOn(api, core, harness, frontendSoot % "runtime->runtime;test->test")
  .settings(
    scalaModule,
    sootBoundary,
    name                := "pag-cli",
    libraryDependencies += "com.github.scopt" %% "scopt" % "4.1.0",
    Compile / mainClass := Some("pag.cli.Main"),
    run / fork          := true,
    // DomainBuildSuite builds the reference domain with the Gradle template
    // (§3) against the api jar this build just packaged.
    Test / javaOptions ++= Seq(
      s"-Dpag.apiJar=${(api / Compile / packageBin).value}",
      s"-Dpag.repoRoot=${(ThisBuild / baseDirectory).value}"
    )
  )
