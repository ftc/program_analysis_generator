package pag.campaign

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Using

import pag.cli.DomainJars.*

/** Evaluating a domain over the smoke corpus (implementation_strategy.md Phase
  * 10), with real pag and domains whose answers are known.
  */
class EvaluateSuite extends munit.FunSuite:

  /** None: killed at the wall-clock limit. */
  def exitCode(r: TargetResult): Option[Int] = r.run match
    case TargetRun.Exited(code, _) => Some(code)
    case TargetRun.Killed          => None

  override val munitTimeout: scala.concurrent.duration.Duration = 2.minutes

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)
  val corpus: Path = repo.resolve("corpora/smoke")
  val probeLib: Path = Paths.get(classOf[pag.probe.Rand].getProtectionDomain.getCodeSource.getLocation.toURI)

  /** pag on this test JVM's classpath, which has cli and the Soot front end. */
  val pagCommand: List[String] = List("java", "-cp", sys.props("java.class.path"), "pag.cli.Main")

  def evaluate(sources: Map[String, String], deadline: scala.concurrent.duration.FiniteDuration = Evaluate.Deadline,
      timeout: scala.concurrent.duration.FiniteDuration = Evaluate.Timeout, corpusDir: Path = corpus): Evaluation =
    withJar(sources) { jar =>
      val work = Files.createTempDirectory("evaluate")
      try Evaluate.run(pagCommand, jar, corpusDir, probeLib, work, deadline, timeout).fold(e => fail(e), identity)
      finally Using.resource(Files.walk(work))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
    }

  def cells(e: Evaluation): Map[String, String] = e.results.map(r => r.target.probe -> r.cell).toMap

  test("the corpus: eight targets, each probe present, three of them reachable"):
    val c = Corpus.read(corpus).fold(e => fail(e), identity)
    assertEquals(c.targets.map(_.probe),
      List("Const1", "Const2", "Sign1", "Range1", "Range2", "Arith1", "Loop1", "Loop2"))
    assertEquals(c.targets.filter(_.reachable).map(_.probe), List("Const2", "Range2", "Loop2"))

  test("ref-interval proves all five unreachable targets and refutes no reachable one"):
    val e = evaluate(intervalSources)
    assertEquals(cells(e), Map("Const1" -> "R", "Const2" -> "A", "Sign1" -> "R", "Range1" -> "R",
      "Range2" -> "A", "Arith1" -> "R", "Loop1" -> "R", "Loop2" -> "A"))
    assertEquals((e.proved, e.unsound), (5, false))

  test("Loop1 needs widening: ref-interval with widen replaced by join does not converge"):
    val noWidening = intervalSources.map { (path, text) =>
      path -> text.replace("    @Override\n    public IntervalState widen(IntervalState prev, IntervalState next) {",
        "    @Override public IntervalState widen(IntervalState a, IntervalState b) { return join(a, b); }\n" +
          "    IntervalState unusedWiden(IntervalState prev, IntervalState next) {")
    }
    assertNotEquals(noWidening, intervalSources, "the edit applied")
    val e = evaluate(noWidening)
    assertEquals(cells(e)("Loop1"), "I")
    assertEquals(e.results.find(_.target.probe == "Loop1").flatMap(r => exitCode(r)), Some(4))

  test("ref-sign proves only the sign target"):
    val e = evaluate(domainSources("ref-sign"))
    assertEquals(cells(e), Map("Const1" -> "A", "Const2" -> "A", "Sign1" -> "R", "Range1" -> "A",
      "Range2" -> "A", "Arith1" -> "A", "Loop1" -> "A", "Loop2" -> "A"))
    assertEquals((e.proved, e.unsound), (1, false))

  test("a domain that refutes everything is caught on every reachable target"):
    val e = evaluate(refutesAllStub)
    assertEquals(e.results.filter(_.cell == "✗").map(_.target.probe), List("Const2", "Range2", "Loop2"))
    assertEquals(e.results.filter(_.target.reachable).map(exitCode), List(Some(3), Some(3), Some(3)))
    assertEquals((e.proved, e.unsound), (5, true))

  test("a domain that throws is a domain failure everywhere, with no verdict"):
    val e = evaluate(throwingStub)
    assertEquals(e.results.map(exitCode).distinct, List(Some(5)))
    assertEquals(e.results.map(_.cell).distinct, List("E"))
    assertEquals((e.proved, e.unsound), (0, false))

  test("a domain that hangs is killed at the wall-clock bound, not waited on"):
    // one target is enough to show it, and keeps the test to one bound's wait
    val dir = Files.createTempDirectory("corpus")
    try
      Files.copy(corpus.resolve("Sign1.java"), dir.resolve("Sign1.java"))
      Files.writeString(dir.resolve("manifest.json"),
        """{"about":"one","targets":[{"probe":"Sign1","reach":1,"reachable":false,"inputs":[1],"rung":1,"provableBy":""}]}""")
      val e = evaluate(Map(stub("Hangs", transfer = "while (true) { }")), deadline = 2.seconds, timeout = 8.seconds,
        corpusDir = dir)
      val r = e.results.head
      assertEquals(r.run, TargetRun.Killed)
      assertEquals(r.cell, "H")
      assert(r.elapsedMs >= 8000 && r.elapsedMs < 20000, r.elapsedMs)
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  test("a slow domain stops at pag's deadline and reports I, before the wall-clock kill"):
    // each transfer sleeps 1 s, so the search outlasts a 2 s deadline but checks it between iterations
    val dir = Files.createTempDirectory("corpus")
    try
      Files.copy(corpus.resolve("Sign1.java"), dir.resolve("Sign1.java"))
      Files.writeString(dir.resolve("manifest.json"),
        """{"about":"one","targets":[{"probe":"Sign1","reach":1,"reachable":false,"inputs":[1],"rung":1,"provableBy":""}]}""")
      val slow = Map(stub("Slow", transfer = "try { Thread.sleep(1000); } catch (InterruptedException e) { } return post;"))
      val r = evaluate(slow, deadline = 2.seconds, timeout = 20.seconds, corpusDir = dir).results.head
      assertEquals((r.cell, exitCode(r)), ("I", Some(4)))
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  test("a deadline at or after the wall-clock kill is refused"):
    intercept[IllegalArgumentException](
      Evaluate.run(pagCommand, Path.of("x.jar"), corpus, probeLib, Path.of("w"), 30.seconds, 30.seconds))

  test("a corpus with a missing probe is an error naming it"):
    val dir = Files.createTempDirectory("corpus")
    try
      Files.copy(corpus.resolve("manifest.json"), dir.resolve("manifest.json"))
      assert(Corpus.read(dir).left.exists(_.contains("Const1")))
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
