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
      assert(r.summary.builds && r.summary.loads, r.build.map(_.last.log))
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
        assert(r.build.exists(_.last.log.contains("error: illegal start of expression")), r.build.map(_.last.log))
    }

  test("a reply stopped at the token budget is recorded as out of tokens"):
    attempt(200 -> completion("```java src/pag/domains/gen/D.java\npackage pag.domains.gen; class D {", finishReason = "length")) {
      (r, _) =>
        assertEquals((r.summary.outOfTokens, r.reply.flatMap(_.finishReason)), (true, Some("length")))
        assertEquals(StatusView.outcome(r), "ran out of tokens")
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
      assertEquals(r.failure.collect { case f: ChatFailure.BadResponse => f.status }, Some(500))
      assert(Files.isRegularFile(dir.resolve("attempt.json")))
    }

  // Whether the domain's own tests compiled (implementation_strategy.md §16, item 25: recorded honestly).

  val brokenTest: (String, String) = "test/pag/domains/gen/BrokenTest.java" ->
    "package pag.domains.gen;\nclass BrokenTest {\n  @org.junit.jupiter.api.Test void t() { Map.of(); }\n}"

  test("tests that compile: recorded as compiled, in the build and the summary"):
    attempt(200 -> completion(reply(intervalReply))) { (r, _) =>
      assert(r.build.exists { case BuildRecord.Compiled(_, _, _: Tests.Ran, _) => true; case _ => false }, r.build)
      assertEquals(r.summary.testsCompiled, Some(true))
      assert(r.summary.testsRun > 0)
    }

  test("tests that do not compile: the domain is still evaluated, and the tests are recorded as not compiling"):
    attempt(200 -> completion(reply(intervalReply + brokenTest))) { (r, _) =>
      assertEquals((r.summary.builds, r.summary.loads, r.summary.proved), (true, true, 5))
      assertEquals((r.summary.testsCompiled, r.summary.testsRun, r.summary.testsFailed), (Some(false), 0, 0))
      val testLog = r.build.collect { case BuildRecord.Compiled(_, _, Tests.DidNotCompile(log), _) => log }
      assert(testLog.exists(_.contains("BrokenTest.java:3: error: cannot find symbol")), testLog)
      assertEquals(Report.tests(r.summary), "did not compile")
    }

  test("no test files: the tests compile, none run, and the cell reads 0/0"):
    attempt(200 -> completion(reply(intervalReply.filter((p, _) => p.startsWith("src/"))))) { (r, _) =>
      assertEquals((r.summary.builds, r.summary.testsCompiled, r.summary.testsRun), (true, Some(true), 0))
      assertEquals(Report.tests(r.summary), "0/0")
    }

  test("a domain that does not compile: its tests are not tried"):
    attempt(200 -> completion(reply(Map("src/pag/domains/gen/D.java" -> "package pag.domains.gen; class D { int x = ; }")))) {
      (r, _) =>
        assertEquals((r.build.map(_.compiled), r.summary.testsCompiled), (Some(false), None))
    }

  test("tests that did not compile survive attempt.json: written out, and decoded the same"):
    attempt(200 -> completion(reply(intervalReply + brokenTest))) { (r, dir) =>
      val json = Files.readString(dir.resolve("attempt.json"))
      assertEquals("\"DidNotCompile\"".r.findAllMatchIn(json).size, 1, "the tests' case, written once")
      assert(!json.contains("\"summary\""), "the summary is derived, never stored")
      assert(json.contains(s"\"schema\": ${AttemptRecord.Schema}"), json.take(200))
      assertEquals(Attempt.read(dir.resolve("attempt.json")), Right(r))
    }

  // Rounds: every round's messages kept exactly, and the record's views describe the last round.

  test("a one-shot attempt is one round, which sent exactly the prompt's messages"):
    attempt(200 -> completion("I am not sure how to do this.")) { (r, _) =>
      assertEquals((r.rounds, r.conversation.failed, r.conversation.last.sent), (1, Nil, prompt.messages))
    }

  test("a record with two failed rounds: written and read back the same, its views on the last round"):
    attempt(200 -> completion(reply(intervalReply))) { (r, dir) =>
      val failedBuild = new BuildRecord.Failed(CompileTry("src/D.java:1: error: x\n1 error\n(exit 1)", false), Nil)
      val replyOf = (text: String) => r.reply.get.copy(content = text)
      def round(n: Int): FailedRound =
        val sent = prompt.messages ++ Option.when(n > 1)(List(ChatMessage("assistant", s"reply ${n - 1}"),
          ChatMessage("user", s"feedback ${n - 1}"))).toList.flatten
        FailedRound(sent, replyOf(s"reply $n"), FilesRecord(List("src/D.java"), 0, Nil), failedBuild, s"feedback $n")
      val lastSent = prompt.messages ++ List(ChatMessage("assistant", "reply 2"), ChatMessage("user", "feedback 2"))
      val three = r.copy(conversation = Conversation(List(round(1), round(2)), r.conversation.last.copy(sent = lastSent)))
      import AttemptRecord.given
      val f = dir.resolve("three.json")
      Files.write(f, io.bullet.borer.Json.encode(three).toByteArray)
      val back = Attempt.read(f).fold(e => fail(e), identity)
      assertEquals(back, three)
      assertEquals(back.rounds, 3)
      assertEquals(back.conversation.failed.map(_.sent.last.content), List(prompt.messages.last.content, "feedback 1"))
      assertEquals((back.summary, back.targets, back.reply), (r.summary, r.targets, r.reply), "the views read the last round")
    }
