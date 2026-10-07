package pag.campaign

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Using

import pag.campaign.FakeServer.{completion, withServer}
import pag.cli.DomainJars.*

/** One attempt end to end (implementation_strategy.md Phase 10), with the fake
  * server playing the model: real prompt, real Gradle build, real pag.
  */
class AttemptSuite extends munit.FunSuite:

  override val munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)
  val prompt: Prompt = Prompt.assemble(repo, "generator-v1").fold(e => throw IllegalStateException(e), identity)
  val tools: Tools = Tools(
    List("java", "-cp", sys.props("java.class.path"), "pag.cli.Main"),
    repo.resolve("domains/build-template"),
    apiPath,
    Paths.get(classOf[pag.probe.Rand].getProtectionDomain.getCodeSource.getLocation.toURI),
    repo.resolve("corpora/smoke")
  )

  /** ref-interval and its tests, moved into the package the prompt asks for, as a reply would give them. */
  val intervalReply: Map[String, String] =
    val domain = repo.resolve("domains/ref-interval")
    Using.resource(Files.walk(domain)) { all =>
      all.iterator.asScala.filter(_.toString.endsWith(".java")).filterNot(_.toString.contains("/build/")).map { f =>
        domain.relativize(f).toString.replace("ref/interval", "gen") ->
          Files.readString(f).replace("pag.domains.ref.interval", "pag.domains.gen").stripTrailing
      }.toMap
    }

  def reply(files: Map[String, String]): String =
    files.toList.sorted.map((p, text) => s"```java $p\n$text\n```").mkString("Here is my domain.\n\n", "\n\n", "\n")

  /** Runs one attempt against a server answering `script`, in a directory deleted afterwards. */
  def attempt(script: (Int, String)*)(check: (AttemptRecord, Path) => Unit): Unit =
    val dir = Files.createTempDirectory("attempt")
    try withServer(script*) { s =>
        val agent = AgentConfig(s.baseUrl, "m.gguf", retries = 0)
        val record = Attempt.run("test-campaign", 1, agent, ChatClient(agent, sleep = _ => ()), prompt, tools,
          dir.resolve("attempt-001"))
        check(record, dir.resolve("attempt-001"))
      }
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  test("a good reply: written, built, its tests pass, and the corpus judges it like ref-interval"):
    attempt(200 -> completion(reply(intervalReply))) { (r, dir) =>
      assertEquals(r.summary.files, intervalReply.size)
      assert(r.summary.builds && r.summary.loads, r.build.map(_.compileLog))
      assertEquals((r.summary.testsRun > 0, r.summary.testsFailed), (true, 0))
      assertEquals(r.summary.cells, List("R", "A", "R", "R", "A", "R", "R", "A"))
      assertEquals((r.summary.proved, r.summary.unsound), (5, false))
      assert(Files.isRegularFile(dir.resolve("domain/src/pag/domains/gen/IntervalDomain.java")))
    }

  test("attempt.json is written, decodes to the same record, and holds the full messages and reply"):
    attempt(200 -> completion(reply(intervalReply), reasoning = Some("I will track intervals."))) { (r, dir) =>
      val read = Attempt.read(dir.resolve("attempt.json")).fold(e => fail(e), identity)
      assertEquals(read, r)
      assertEquals(read.prompt.messages, prompt.messages)
      assertEquals((read.prompt.version, read.prompt.sha256), (prompt.version, prompt.sha256))
      assertEquals(read.reply.flatMap(_.reasoning), Some("I will track intervals."))
      assertEquals((read.campaign, read.attempt, read.sample, read.envelope.profile),
        ("test-campaign", "attempt-001", 1, "bigint-main-v1"))
      assert(!Files.exists(dir.resolve("attempt.json.tmp")))
    }

  test("a domain whose own tests fail is still evaluated, and the failure is counted"):
    val failingTest = "test/pag/domains/gen/BrokenTest.java" ->
      ("package pag.domains.gen;\nclass BrokenTest {\n  @org.junit.jupiter.api.Test void wrong() {\n" +
        "    org.junit.jupiter.api.Assertions.assertEquals(1, 2);\n  }\n}")
    attempt(200 -> completion(reply(intervalReply + failingTest))) { (r, _) =>
      assertEquals((r.summary.builds, r.summary.testsFailed), (true, 1))
      assertEquals(r.summary.proved, 5)
    }

  test("a reply that does not compile: not built, not evaluated, the compiler's errors kept"):
    attempt(200 -> completion(reply(Map("src/pag/domains/gen/D.java" -> "package pag.domains.gen; class D { int x = ; }")))) {
      (r, _) =>
        assertEquals((r.summary.builds, r.targets, r.summary.cells), (false, Nil, Nil))
        assert(r.build.exists(_.compileLog.contains("error: illegal start of expression")), r.build.map(_.compileLog))
    }

  test("a reply with no files: nothing built, and the reply is still kept"):
    attempt(200 -> completion("I am not sure how to do this.")) { (r, _) =>
      assertEquals((r.summary.files, r.build), (0, None))
      assertEquals(r.reply.map(_.content), Some("I am not sure how to do this."))
    }

  test("an unsafe path in a reply is refused and recorded, and nothing is written outside the attempt"):
    attempt(200 -> completion(reply(Map("../escape.java" -> "class X {}")))) { (r, dir) =>
      assertEquals(r.files.map(_.problems), Some(List("refused path: ../escape.java")))
      assert(!Files.exists(dir.resolve("escape.java")) && !Files.exists(dir.getParent.resolve("escape.java")))
    }

  test("the model is unreachable: the failure is recorded and the attempt still finishes"):
    attempt(500 -> "down") { (r, dir) =>
      assertEquals((r.reply, r.files, r.build), (None, None, None))
      assertEquals(r.failure.map(_.status), Some(Some(500)))
      assert(Files.isRegularFile(dir.resolve("attempt.json")))
    }
