package pag.campaign

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Using

import pag.cli.DomainJars.apiPath

/** Build retries (implementation_strategy.md §16, item 25): a compile that fails
  * without javac errors is tried again, up to three tries; one with javac errors
  * is not. A fake `gradlew` plays Gradle for the exact cases; real Gradle checks
  * that a build killed at the limit does not block the next.
  */
class BuildSuite extends munit.FunSuite:

  override val munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)

  /** Runs `body` with a template whose `gradlew` is `script` (sh), and a domain directory; both deleted afterwards. */
  def withFake[A](script: String)(body: (Path, Path) => A): A =
    val root = Files.createTempDirectory("build")
    try
      val template = Files.createDirectories(root.resolve("template"))
      val gradlew = template.resolve("gradlew")
      Files.writeString(gradlew, "#!/bin/sh\n" +
        "for a; do last=$a; case $a in -PdomainDir=*) d=${a#-PdomainDir=};; esac; done\n" +
        s"""echo "$$last" >> "$$(dirname "$$0")/calls"\n""" + script)
      Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"))
      body(template, Files.createDirectories(root.resolve("domain")))
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  /** The tasks the fake was called with, in order. */
  def calls(template: Path): List[String] = Files.readAllLines(template.resolve("calls")).asScala.toList

  val writeJar: String = """mkdir -p "$d/build/libs" && touch "$d/build/libs/$(basename "$d").jar"; exit 0"""

  test("Gradle failing without javac errors: three tries, not timed out, every log kept"):
    withFake("""echo "FAILURE: could not start the daemon"; exit 1""") { (template, domain) =>
      val b = Build.run(template, domain, apiPath)
      assertEquals((b.jar, b.compileTries, b.compileTimedOut, b.testsCompiled), (None, 3, false, None))
      assertEquals(calls(template), List("jar", "jar", "jar"))
      assertEquals(b.earlierCompileLogs.size, 2)
      assert((b.compileLog :: b.earlierCompileLogs).forall(_.contains("could not start the daemon")))
    }

  test("a compile killed at the limit on every try: three tries, recorded as timed out"):
    withFake("sleep 30") { (template, domain) =>
      val b = Build.run(template, domain, apiPath, timeout = 300.millis)
      assertEquals((b.jar, b.compileTries, b.compileTimedOut), (None, 3, true))
      assert(b.compileLog.endsWith("(killed at the time limit)"), b.compileLog)
    }

  test("a compile that fails once and then builds: two tries, built, its tests compiled and run"):
    withFake(s"""[ "$$last" = jar ] && [ ! -e "$$(dirname "$$0")/failed" ] && { touch "$$(dirname "$$0")/failed"; exit 1; }\n$writeJar""") {
      (template, domain) =>
        val b = Build.run(template, domain, apiPath)
        assertEquals((b.jar.isDefined, b.compileTries, b.compileTimedOut, b.earlierCompileLogs.size), (true, 2, false, 1))
        assertEquals(calls(template), List("jar", "jar", "testClasses", "test"))
    }

  test("a compile that fails with javac's errors: one try, since the errors are the model's"):
    withFake("""printf '%s\n' "$d/src/D.java:1: error: illegal start of expression" "  int x = ;" "          ^" "1 error"; exit 1""") {
      (template, domain) =>
        val b = Build.run(template, domain, apiPath)
        assertEquals((b.jar, b.compileTries, b.earlierCompileLogs), (None, 1, Nil))
        assertEquals(calls(template), List("jar"))
    }

  test("a compile that succeeds at once: one try, no earlier logs"):
    withFake(writeJar) { (template, domain) =>
      val b = Build.run(template, domain, apiPath)
      assertEquals((b.jar.isDefined, b.compileTries, b.compileTimedOut, b.earlierCompileLogs), (true, 1, false, Nil))
    }

  test("real Gradle: a build killed at the limit, three times, does not block the next build of the same domain"):
    val root = Files.createTempDirectory("build")
    try
      val domain = root.resolve("domain")
      Files.createDirectories(domain.resolve("src/x"))
      Files.writeString(domain.resolve("src/x/D.java"), "package x; public class D {}")
      // 200 ms is less than the Gradle client's own start-up, so every try is killed.
      val killed = Build.run(repo.resolve("domains/build-template"), domain, apiPath, timeout = 200.millis)
      assertEquals((killed.compileTries, killed.compileTimedOut), (3, true), "the first build must really be killed")
      val b = Build.run(repo.resolve("domains/build-template"), domain, apiPath)
      assert(b.jar.isDefined, b.compileLog)
      assertEquals((b.compileTries, b.compileTimedOut), (1, false))
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
