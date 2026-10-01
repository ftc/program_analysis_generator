package pag.cli

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** The reference domain, built and tested through the Gradle build template
  * (implementation_strategy.md §3, "How domains are built"; Phase 3), so that
  * `sbt test` passing means the reference domain builds and its tests pass too.
  */
class DomainBuildSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min") // the first run downloads Gradle and JUnit

  val repoRoot: Path = Paths.get(sys.props("pag.repoRoot"))
  val apiJar: Path = Paths.get(sys.props("pag.apiJar"))
  val template: Path = repoRoot.resolve("domains/build-template")
  val interval: Path = repoRoot.resolve("domains/interval")

  test("the template builds the reference domain against the api jar alone, and its tests pass"):
    val command = List(template.resolve("gradlew").toString, "-p", template.toString,
      s"-PdomainDir=$interval", s"-PapiJar=$apiJar", "clean", "build")
    val log = Files.createTempFile("gradle-build", ".txt")
    try
      val process = ProcessBuilder(command.asJava).redirectErrorStream(true).redirectOutput(log.toFile).start()
      assert(process.waitFor(10, TimeUnit.MINUTES), "gradle did not finish")
      assertEquals(process.exitValue, 0, Files.readString(log))
    finally Files.deleteIfExists(log)
    assert(Files.exists(interval.resolve("build/libs/interval.jar")), "no domain jar")
    // JUnit's report: at least one test ran, and none failed.
    val reports = Files.list(interval.resolve("build/test-results/test")).iterator.asScala
      .filter(_.toString.endsWith(".xml")).map(Files.readString(_)).toList
    assert(reports.nonEmpty, "no test report")
    assert(reports.forall(r => r.contains("""failures="0"""") && r.contains("""errors="0"""")), reports.mkString)
    assert(!reports.exists(_.contains("""tests="0"""")), "a report with no tests")
