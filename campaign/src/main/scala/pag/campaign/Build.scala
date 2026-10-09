package pag.campaign

import java.nio.file.{Files, Path}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** How a generated domain built: its jar if it compiled, and how its own tests did. */
final case class BuildResult(
    jar: Option[Path], // None: did not compile
    compileLog: String,
    testsRun: Int,
    testFailures: Int, // failed assertions
    testErrors: Int, // tests that threw
    testLog: String, // the test compile's log if it failed, else the test run's
    elapsedMs: Long,
    testsCompiled: Option[Boolean] // None: not tried, because the domain did not compile
)

/** Builds a domain with the fixed Gradle template (implementation_strategy.md §3,
  * "How domains are built"). Three Gradle runs, deliberately: `jar` compiles and
  * packages, `testClasses` compiles the domain's own tests, and `test` runs them.
  * The middle run's exit code is what tells tests that did not compile from no
  * tests at all. A domain whose tests fail, or do not compile, is still
  * evaluated — the smoke corpus is what judges it, and its tests are a column of
  * their own (experiments.md, Table 1).
  */
object Build:

  def run(template: Path, domainDir: Path, api: Path, timeout: FiniteDuration = 5.minutes,
      stage: String => Unit = _ => ()): BuildResult =
    val gradle = List(template.resolve("gradlew").toString, "-p", template.toString, "--console=plain",
      s"-PdomainDir=$domainDir", s"-PapiJar=$api")
    val started = System.currentTimeMillis()
    stage("compiling")
    val compile = Processes.run(gradle :+ "jar", timeout)
    val jar = domainDir.resolve(s"build/libs/${domainDir.getFileName}.jar")
    val compiled = compile.exitCode.contains(0) && Files.isRegularFile(jar)
    val testCompile = Option.when(compiled) { stage("compiling its tests"); Processes.run(gradle :+ "testClasses", timeout) }
    val testsCompiled = testCompile.map(_.exitCode.contains(0))
    val test = Option.when(testsCompiled.contains(true)) { stage("running its tests"); Processes.run(gradle :+ "test", timeout) }
    val (run, failures, errors) =
      if test.isDefined then junitCounts(domainDir.resolve("build/test-results/test")) else (0, 0, 0)
    BuildResult(Option.when(compiled)(jar), log(compile), run, failures, errors, test.orElse(testCompile).fold("")(log),
      System.currentTimeMillis() - started, testsCompiled)

  private def log(p: ProcessResult): String =
    val ending = if p.timedOut then "(killed at the time limit)" else s"(exit ${p.exitCode.getOrElse("?")})"
    s"${p.stdout}${p.stderr}\n$ending"

  /** Tests run, failures and errors, summed over JUnit's XML reports. */
  private def junitCounts(reports: Path): (Int, Int, Int) =
    if !Files.isDirectory(reports) then (0, 0, 0)
    else
      def count(xml: String, attribute: String): Int =
        s"""<testsuite [^>]*\\b$attribute="(\\d+)"""".r.findFirstMatchIn(xml).fold(0)(_.group(1).toInt)
      Using.resource(Files.list(reports)) { files =>
        files.iterator.asScala.filter(_.toString.endsWith(".xml")).map(Files.readString).toList
      }.foldLeft((0, 0, 0)) { case ((t, f, e), xml) => (t + count(xml, "tests"), f + count(xml, "failures"), e + count(xml, "errors")) }
