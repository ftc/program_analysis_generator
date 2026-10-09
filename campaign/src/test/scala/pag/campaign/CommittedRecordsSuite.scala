package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Every attempt record committed under `results/` still decodes. Records are
  * history: a field added to `AttemptRecord` must not make them unreadable.
  */
class CommittedRecordsSuite extends munit.FunSuite:

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)

  val records: List[Path] =
    Using.resource(Files.walk(repo.resolve("results"))) { all =>
      all.iterator.asScala.filter(_.getFileName.toString == "attempt.json").toList.sorted
    }

  test("there are committed records to check"):
    assert(records.nonEmpty)

  test("every committed attempt.json decodes"):
    val failures = records.flatMap(f => Attempt.read(f).left.toOption.map(e => s"$f: $e"))
    assertEquals(failures, Nil)

  test("records from before 2026-10-09 decode with testsCompiled unknown"):
    val e1 = records.filter(_.toString.contains("/e1-rung0-")).map(f => Attempt.read(f).fold(e => fail(e), identity))
    assertEquals(e1.size, 15)
    assert(e1.forall(r => r.summary.testsCompiled.isEmpty && r.build.forall(_.testsCompiled.isEmpty)))
