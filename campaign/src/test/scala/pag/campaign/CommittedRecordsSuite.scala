package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import io.bullet.borer.Json

/** Every attempt record committed under `results/` still reads. Records are
  * history: they are never rewritten, so a change to `AttemptRecord` must keep
  * them readable, and convert them without changing what they say.
  */
class CommittedRecordsSuite extends munit.FunSuite:

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)

  val records: List[Path] =
    Using.resource(Files.walk(repo.resolve("results"))) { all =>
      all.iterator.asScala.filter(_.getFileName.toString == "attempt.json").toList.sorted
    }

  /** As stored: the flat schema 1, its summary and cells included. */
  def stored(f: Path): AttemptRecordV1.Attempt =
    import AttemptRecordV1.given
    Json.decode(Files.readAllBytes(f)).to[AttemptRecordV1.Attempt].valueEither.fold(e => fail(s"$f: $e"), identity)

  def read(f: Path): AttemptRecord = Attempt.read(f).fold(e => fail(e), identity)

  test("there are committed records to check: the 15 E1 attempts (the pipeline check finished none)"):
    assertEquals(records.size, 15)

  test("every committed attempt.json reads, as schema 1"):
    assertEquals(records.flatMap(f => Attempt.read(f).left.toOption), Nil)
    assert(records.forall(f => read(f).schema == 1))

  test("converting changes nothing they say: the derived summary equals the stored one, cells included"):
    for f <- records do
      val s = stored(f).summary
      assertEquals(read(f).summary, Summary(s.outOfTokens, s.files, s.builds, s.testsRun, s.testsFailed, s.loads,
        s.cells, s.proved, s.unsound, s.testsCompiled), f)

  test("the reply, the files and every target's result survive conversion unchanged"):
    for f <- records do
      val (old, now) = (stored(f), read(f))
      assertEquals(now.reply, old.reply, f)
      assertEquals(now.files.map(_.written), old.files.map(_.written), f)
      assertEquals(now.targets.map(t => (t.probe, t.cell, t.check)), old.targets.map(t => (t.probe, t.cell, t.check)), f)
      assertEquals(now.build.map(_.last.log), old.build.map(_.compileLog), f)

  test("records from before 2026-10-09: one compile try, and whether the tests compiled not recorded"):
    val built = records.map(read).flatMap(_.build)
    assertEquals(built.size, 13)
    assert(built.forall(_.earlier.isEmpty))
    assert(built.collect { case BuildRecord.Compiled(_, _, t, _) => t }.forall(_.isInstanceOf[Tests.CompileNotRecorded]))

  // Combinations schema 1's writer could not produce are refused, never guessed at.

  val evaluated: AttemptRecordV1.Attempt =
    stored(records.find(_.toString.contains("e1-rung0-qwen3.5-27B/attempt-003")).get)

  test("schema 1 refused: a reply and a failure together"):
    val both = evaluated.copy(failure = Some(AttemptRecordV1.ChatFailure("x", None, None, 1)))
    assert(AttemptRecordV1.convert(both).isLeft)

  test("schema 1 refused: a target killed yet with an exit code"):
    val t = evaluated.targets.head
    assert(AttemptRecordV1.convert(evaluated.copy(targets = t.copy(timedOut = true) :: evaluated.targets.tail)).isLeft)

  test("schema 1 refused: targets without a build that compiled"):
    val notBuilt = evaluated.copy(build = evaluated.build.map(_.copy(compiled = false)))
    assert(AttemptRecordV1.convert(notBuilt).isLeft)

  test("schema 1: an evaluation error kept among the files' problems becomes the evaluation's failure"):
    val failedRun = evaluated.copy(targets = Nil,
      files = evaluated.files.map(f => f.copy(problems = f.problems :+ "evaluation: probe Sign1 did not compile")))
    val now = AttemptRecordV1.convert(failedRun).fold(e => fail(e), identity)
    assertEquals(now.build.collect { case BuildRecord.Compiled(_, _, _, e) => e },
      Some(EvaluationRecord.Failed("probe Sign1 did not compile")))
    assertEquals(now.files.map(_.problems), Some(Nil))

  test("a schema this code does not know is an error, not a guess"):
    val dir = Files.createTempDirectory("records")
    try
      val f = dir.resolve("attempt.json")
      Files.writeString(f, """{"schema": 99, "campaign": "c"}""")
      assert(Attempt.read(f).left.exists(_.contains("schema 99")), Attempt.read(f))
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  test("records from before rounds convert to one round, which sent the record's prompt"):
    for f <- records do
      val r = read(f)
      assertEquals((r.rounds, r.conversation.last.sent), (1, stored(f).prompt.messages), f)
