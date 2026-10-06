package pag.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import pag.cli.DomainJars.*
import pag.frontend.Fixtures
import pag.results.{CheckResult, Outcome, Verdict, Wire}
import pag.results.Codecs.given

/** Phase 5's done-when (implementation_strategy.md §14): a deliberately broken
  * transfer function produces a refutation, a hand-written reaching run
  * contradicts it, and the rejection is recorded and replays. The whole idea,
  * with a human standing in for the adversary and this test for the driver.
  *
  * The mutant is domains/mut-add-off-by-one; the probe, fixtures/OffByOne.java,
  * reaches reach(1) exactly when x = 9.
  */
class RejectionSuite extends munit.FunSuite:

  val mutant: String = "mut-add-off-by-one"

  def run(command: String, sources: Map[String, String], classes: Path, flags: String*): Pag.Result =
    withJar(sources) { jar =>
      Pag((List(command, "--domain", jar.toString, "--classes", classes.toString) ++ flags)*)
    }

  def decoded(r: Pag.Result): CheckResult =
    Wire.decode[CheckResult](r.out.getBytes(UTF_8)).fold(e => fail(s"$e\n${r.out}"), identity)

  def lastLine(r: Pag.Result): String = r.out.trim.linesIterator.toList.last

  test("a broken transfer refutes, a hand-written run contradicts it, and the rejection replays"):
    val sources = domainSources(mutant)
    Fixtures.withCompiled("OffByOne") { classes =>
      // 1. The refutation.
      val analyzed = run("analyze", sources, classes, "--reach", "1")
      assertEquals((analyzed.exit, lastLine(analyzed)), (0, "REFUTED"), analyzed.out)

      // 2. The hand-written reaching run: x = 9.
      val checked = run("check", sources, classes, "--reach", "1", "--inputs", "9", "--json")
      assertEquals(checked.exit, 3, checked.out + checked.err)
      val result = decoded(checked)
      assertEquals((result.analysis.verdict, result.run.reached, result.outcome),
        (Verdict.Refuted, List(BigInt(1)), Outcome.Unsound))

      // 3. The rejection, recorded the way the driver will (§3): the probe's source and run.json.
      val repo = Files.createTempDirectory("repo")
      try
        val rejection = Files.createDirectories(repo.resolve(s"domains/$mutant/rejections/r1"))
        Files.writeString(rejection.resolve("OffByOne.java"), Fixtures.source("OffByOne"))
        Files.write(rejection.resolve("run.json"), checked.out.trim.getBytes(UTF_8))

        // 4. Read back, and replayed from the record alone: recompile the source, check again.
        val recorded = Wire.decode[CheckResult](Files.readAllBytes(rejection.resolve("run.json")))
          .fold(e => fail(e), identity)
        assertEquals(recorded, result)
        Fixtures.withCompiledSource(rejection.resolve("OffByOne.java")) { replayClasses =>
          val replayed = run("check", sources, replayClasses, "--reach", recorded.query.id.toString,
            "--inputs", recorded.inputs.mkString(","), "--json")
          assertEquals(replayed.exit, 3)
          assertEquals((decoded(replayed).outcome, decoded(replayed).run.reached), (Outcome.Unsound, List(BigInt(1))))
        }
      finally Using.resource(Files.walk(repo))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
    }

  // --- Controls: the bug, not the probe or the input, is what is caught

  test("the reference domain does not refute the same target, and the same run confirms its alarm"):
    Fixtures.withCompiled("OffByOne") { classes =>
      val r = run("check", intervalSources, classes, "--reach", "1", "--inputs", "9")
      assertEquals(r.exit, 0)
      assertEquals(lastLine(r), "CONSISTENT — an alarm, and this run reaches reach(1): the alarm is real")
    }

  test("the mutant's refutation stands against an input that misses the target"):
    Fixtures.withCompiled("OffByOne") { classes =>
      val r = run("check", domainSources(mutant), classes, "--reach", "1", "--inputs", "3")
      assertEquals(r.exit, 0)
      assertEquals(lastLine(r), "CONSISTENT — refuted, and this run does not reach reach(1)")
    }

  // --- The mutant is what domain.json says it is

  test("the mutant differs from ref-interval only by its name, header and the one planted bug"):
    def strip(text: String) = text.replace("pag.domains.mut.addoffbyone", "pag.domains.ref.interval")
    val ref = intervalSources.map((k, v) => k.replace("ref/interval", "mut/addoffbyone") -> v)
    val mut = domainSources(mutant)
    assertEquals(mut.keySet, ref.keySet)
    for (path, text) <- mut if !path.endsWith("IntervalDomain.java") do
      assertEquals(strip(text), ref(path), s"$path should be the reference's, package aside")
    val domain = mut.collectFirst { case (p, t) if p.endsWith("IntervalDomain.java") => t }.get
    assertEquals("// PLANTED BUG".r.findAllIn(domain).size, 1, "exactly one planted-bug marker")

  test("domain.json marks it a mutant and names the bug, and the mutant corpus lists it"):
    val json = Files.readString(repoRoot.resolve(s"domains/$mutant/domain.json"))
    assert(json.contains(s"\"id\": \"$mutant\"") && json.contains("\"status\": \"mutant\""), json)
    assert(json.contains("\"plantedBug\""), json)
    val corpus = Files.readAllLines(repoRoot.resolve("corpora/mutants.txt")).asScala.map(_.trim)
    assert(corpus.contains(mutant), corpus)
