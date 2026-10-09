package pag.campaign

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Build feedback's message (implementation_strategy.md §16, item 25): javac's
  * errors once each, relative paths, capped; and the two templates filled.
  */
class FeedbackSuite extends munit.FunSuite:

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)

  /** Where the GPU server built the committed attempts' domains. */
  def serverDomain(campaign: String, attempt: String): Path =
    Paths.get(s"/home/s/Documents/source/program_analysis_generator/results/$campaign/$attempt/domain")

  // Any indentation: Gradle's repeat of the report is indented, and must not get through.
  val Header: scala.util.matching.Regex = """(?m)^\s*\S.*\.java:\d+: error: """.r

  /** Every committed attempt whose domain did not compile, with javac's count from its own log. */
  val failedBuilds: List[(AttemptRecord, Int)] =
    Using.resource(Files.walk(repo.resolve("results"))) { all =>
      all.iterator.asScala.filter(_.getFileName.toString == "attempt.json").toList.sorted
    }.map(f => Attempt.read(f).fold(e => fail(e), identity))
      .flatMap(r => r.build.filter(b => !b.compiled).map(b => r -> """(?m)^(\d+) errors?$""".r
        .findFirstMatchIn(b.compileLog).fold(-1)(_.group(1).toInt)))

  test("the committed failed builds are the 12 known from findings.md"):
    assertEquals(failedBuilds.size, 12)
    assert(failedBuilds.forall(_._2 > 0), failedBuilds.map((r, n) => s"${r.campaign}/${r.attempt}: $n"))

  test("every committed failed build: each of javac's errors once, no absolute paths, within the cap"):
    for (r, count) <- failedBuilds do
      val name = s"${r.campaign}/${r.attempt}"
      val text = Feedback.errors(r.build.get.compileLog, serverDomain(r.campaign, r.attempt)).getOrElse(fail(s"$name: none"))
      assertEquals(Header.findAllMatchIn(text).size, count, name)
      assert(!text.contains("/home/"), s"$name:\n$text")
      assert(text.getBytes(UTF_8).length <= Feedback.Cap, name)
      assert(text.endsWith(s"$count error" + (if count == 1 then "" else "s")), name)

  test("Qwen3.5-9B sample 2, worked by hand: its five errors in javac's order, carets kept"):
    val r = failedBuilds.map(_._1).find(r => r.campaign == "e1-rung0-qwen3.5-9B" && r.attempt == "attempt-002").get
    val text = Feedback.errors(r.build.get.compileLog, serverDomain(r.campaign, r.attempt)).get
    val f = "src/pag/domains/gen/IntervalDomain.java"
    val (carets, rest) = text.linesIterator.toList.partition(_.trim == "^")
    assertEquals(carets.size, 5)
    assertEquals(rest, List(
      s"$f:22: error: cannot find symbol",
      "public class IntervalDomain implements Domain<IntervalState> {",
      "  symbol: class IntervalState",
      s"$f:259: error: cannot find symbol",
      "            case Assign(Assign.Target target, RVal source) -> {",
      "  symbol:   class Assign",
      "  location: class IntervalDomain",
      s"$f:259: error: package Assign does not exist",
      "            case Assign(Assign.Target target, RVal source) -> {",
      s"$f:273: error: cannot find symbol",
      "            case Assume(RVal cond) -> {",
      "  symbol:   class Assume",
      "  location: class IntervalDomain",
      s"$f:290: error: cannot find symbol",
      "            case Call(Optional<LVal.Local> target, MethodId callee, List<RVal> args) -> {",
      "  symbol:   class Call",
      "  location: class IntervalDomain",
      "5 errors"
    ))

  val dir: Path = Paths.get("/work/attempt-001/domain")

  /** A Gradle-shaped log: javac's report, then the failure summary repeating it indented. */
  def log(errors: List[String]): String =
    val report = errors :+ s"${errors.size / 3} errors"
    (List("> Task :compileJava FAILED") ++ report ++ List("", "FAILURE: Build failed with an exception.") ++
      report.map("  " + _)).mkString("\n")

  def error(n: Int): List[String] = List(s"$dir/src/D.java:$n: error: something wrong, number $n", "    int x = ;", "            ^")

  test("over the cap: whole errors only, a note of how many were left out, and javac's count"):
    val text = Feedback.errors(log((1 to 200).toList.flatMap(error)), dir).get
    assert(text.getBytes(UTF_8).length <= Feedback.Cap)
    val shown = Header.findAllMatchIn(text).size
    assert(shown > 10 && shown < 200, shown)
    val lines = text.linesIterator.toList
    assertEquals(lines.takeRight(2), List(s"… ${200 - shown} more not shown", "200 errors"))
    assertEquals(lines.dropRight(2), (1 to shown).toList.flatMap(error).map(_.replace(s"$dir/", "")))

  test("one error longer than the cap alone: cut, marked, and still within the cap"):
    val long = List(s"$dir/src/D.java:1: error: too long", "x" * (Feedback.Cap * 2), "^", "", "", "")
    val text = Feedback.errors(log(long.take(3) ++ error(2)), dir).get
    assert(text.getBytes(UTF_8).length <= Feedback.Cap)
    assert(text.startsWith("src/D.java:1: error: too long"), text.take(100))
    assert(text.contains(" …\n… 1 more not shown\n"), text.takeRight(100))

  test("a cut never splits a character"):
    val wide = List(s"$dir/src/D.java:1: error: wide", "é" * Feedback.Cap, "^")
    val text = Feedback.errors(log(wide), dir).get
    assert(text.getBytes(UTF_8).length <= Feedback.Cap)
    assert(!text.contains("�"))

  test("no complete javac report: none, so the caller can tell a failure of another kind"):
    assertEquals(Feedback.errors("", dir), None)
    assertEquals(Feedback.errors("> Task :compileJava\n\n(killed at the time limit)", dir), None)
    assertEquals(Feedback.errors(s"$dir/src/D.java:1: error: cut off by the time limit\n(killed at the time limit)", dir), None)
    assertEquals(Feedback.errors("FAILURE: Build failed with an exception.\n* What went wrong:\nCould not start daemon", dir), None)

  test("one error: javac's singular count is kept"):
    val text = Feedback.errors(s"${error(1).mkString("\n")}\n1 error\n", dir).get
    assertEquals(text.linesIterator.toList.last, "1 error")

  test("the v1 templates: errors and timeout filled, no slot left, the same text hashed the same"):
    val t = Feedback.load(repo, "feedback-build-v1").fold(e => fail(e), identity)
    val onErrors = Feedback.onErrors(t, "compiling the domain", "src/D.java:1: error: x\n1 error").fold(e => fail(e), identity)
    assert(onErrors.contains("These are the errors from compiling the domain:"), onErrors)
    assert(onErrors.contains("```\nsrc/D.java:1: error: x\n1 error\n```"), onErrors)
    val onTimeout = Feedback.onTimeout(t, 2, 3).fold(e => fail(e), identity)
    assert(onTimeout.contains("more than 2 minutes,\non each of 3 tries."), onTimeout)
    assert(!onErrors.contains("{{") && !onTimeout.contains("{{"))
    assert(onErrors.contains("every file again, complete and corrected") && onTimeout.contains("every file again"))
    assertEquals(Feedback.load(repo, "feedback-build-v1").map(_.sha256), Right(t.sha256))

  test("a missing template version is an error, not an empty message"):
    assert(Feedback.load(repo, "feedback-build-v0").left.exists(_.contains("missing feedback template")))
