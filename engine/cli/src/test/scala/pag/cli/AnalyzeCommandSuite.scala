package pag.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

import pag.cli.DomainJars.*
import pag.cli.Pag.{Result, golden}

/** `pag analyze` end to end (implementation_strategy.md §11, Phase 4's
  * done-when): the reference interval domain, built from its sources, on probes
  * compiled in the test.
  */
class AnalyzeCommandSuite extends munit.FunSuite:

  def analyze(fixture: String, sources: Map[String, String], flags: String*): Result =
    Pag.withDomain("analyze", fixture, sources, flags*)

  /** The temp directory and the timing vary run to run. */
  def masked(out: String): String =
    out.replaceAll("(?m)^classes   \\S+", "classes   <dir>").replaceAll("\\d+ms", "<n>ms")

  /** Reviewed by hand against AnalyzeRefute.java: backward from reach(1), y <= -1, then x <= -2,
    * which contradicts the branch's x > 0, so everything before pre(5) is bottom. 26 locations
    * (12 commands, pre and post, plus entry and exit); 27 edges (one more for each of the two ifs).
    */
  test("golden: an unreachable target is REFUTED, exit 0"):
    val r = analyze("AnalyzeRefute", intervalSources, "--reach", "1")
    assertEquals((r.exit, r.err), (0, ""))
    assertEquals(masked(r.out), golden("AnalyzeRefute-analyze.txt"))

  test("a reachable target is an ALARM, exit 0"):
    val r = analyze("AnalyzeAlarm", intervalSources, "--reach", "1")
    assertEquals((r.exit, r.err), (0, ""))
    assert(r.out.contains("certified   27/27 edges inductive"), r.out)
    assert(r.out.trim.endsWith("ALARM — could not prove reach(1) unreachable"), r.out)

  test("a domain that throws in transfer: DOMAIN FAILURE, exit 5, stack trace on stderr"):
    val r = analyze("AnalyzeRefute", throwingStub, "--reach", "1")
    assertEquals(r.exit, 5)
    assert(r.out.trim.endsWith("DOMAIN FAILURE — transfer threw java.lang.IllegalStateException: boom"), r.out)
    assert(r.out.contains("certified   not run"), r.out)
    assert(r.err.contains("at stub.Throws.transfer"), r.err)

  test("the iteration limit: INCONCLUSIVE, exit 4, with the unexplored count"):
    val r = analyze("AnalyzeRefute", intervalSources, "--reach", "1", "--iteration-limit", "2")
    assertEquals(r.exit, 4)
    assert(r.out.trim.linesIterator.toList.last.startsWith("INCONCLUSIVE — iteration limit 2 reached"), r.out)

  test("--deadline takes a duration"):
    assertEquals(analyze("AnalyzeRefute", intervalSources, "--reach", "1", "--deadline", "30s").exit, 0)
    val bad = analyze("AnalyzeRefute", intervalSources, "--reach", "1", "--deadline", "soon")
    assertEquals(bad.exit, 1)

  test("--all shows post locations, nops and exit"):
    val r = analyze("AnalyzeRefute", intervalSources, "--reach", "1", "--all")
    assert(r.out.contains("  post(10) "), r.out)
    assert(r.out.contains("  exit "), r.out)
    assert(r.out.contains("nop"), r.out)
    assert(!r.out.contains("elided"), r.out)

  test("an unknown reach id is a usage error, exit 1"):
    val r = analyze("AnalyzeRefute", intervalSources, "--reach", "7")
    assertEquals(r.exit, 1)
    assert(r.err.contains("no reach(7) call; the program has reach(1)"), r.err)

  test("a bad domain jar is a usage error, exit 1"):
    val r = analyze("AnalyzeRefute", Map("stub/Helper.java" -> "package stub; public class Helper {}"), "--reach", "1")
    assertEquals(r.exit, 1)
    assert(r.err.contains("no public, concrete class implements pag.api.Domain"), r.err)

  test("a program outside the profile exits 2"):
    val r = analyze("DuplicateReach", intervalSources, "--reach", "7")
    assertEquals(r.exit, 2)

  test("there is no --no-enforce, and --domain, --classes and --reach are required"):
    assertEquals(analyze("AnalyzeRefute", intervalSources, "--reach", "1", "--no-enforce").exit, 1)
    assertEquals(Pag("analyze", "--reach", "1").exit, 1)

  test("an engine VirtualMachineError is inconclusive, exit 4, never 3"):
    val err = ByteArrayOutputStream()
    val exit = Main.engineGuarded(PrintStream(err, true, UTF_8))(throw StackOverflowError())
    assertEquals(exit, 4)
    assert(err.toString(UTF_8).contains("the engine ran out of resources"))
    assertEquals(Main.engineGuarded(PrintStream(err, true, UTF_8))(0), 0, "and it passes other results through")
