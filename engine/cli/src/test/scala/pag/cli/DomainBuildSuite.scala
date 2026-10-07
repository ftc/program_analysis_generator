package pag.cli

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

import pag.cli.DomainJars.{apiPath, repoRoot}

/** The reference domains, built and tested through the Gradle build template
  * (implementation_strategy.md §3, "How domains are built"; Phase 3), so that
  * `sbt test` passing means every reference domain builds and its tests pass too.
  */
class DomainBuildSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = scala.concurrent.duration.Duration(10, "min") // the first run downloads Gradle and JUnit

  val template: Path = repoRoot.resolve("domains/build-template")

  for id <- List("ref-interval", "ref-sign") do
    test(s"the template builds $id against the api alone, and its tests pass"):
      val domain = repoRoot.resolve(s"domains/$id")
      val command = List(template.resolve("gradlew").toString, "-p", template.toString,
        s"-PdomainDir=$domain", s"-PapiJar=$apiPath", "clean", "build")
      val log = Files.createTempFile("gradle-build", ".txt")
      try
        val process = ProcessBuilder(command.asJava).redirectErrorStream(true).redirectOutput(log.toFile).start()
        assert(process.waitFor(10, TimeUnit.MINUTES), "gradle did not finish")
        assertEquals(process.exitValue, 0, Files.readString(log))
      finally Files.deleteIfExists(log)
      assert(Files.exists(domain.resolve(s"build/libs/$id.jar")), "no domain jar")
      // JUnit's report: at least one test ran, and none failed.
      val reports = Files.list(domain.resolve("build/test-results/test")).iterator.asScala
        .filter(_.toString.endsWith(".xml")).map(Files.readString).toList
      assert(reports.nonEmpty, "no test report")
      assert(reports.forall(r => r.contains("""failures="0"""") && r.contains("""errors="0"""")), reports.mkString)
      assert(!reports.exists(_.contains("""tests="0"""")), "a report with no tests")
