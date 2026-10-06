package pag.cli

import java.nio.charset.StandardCharsets.UTF_8

import pag.cli.DomainJars.*
import pag.results.{CheckResult, Incomplete, Outcome, Reachable, Verdict, Wire}
import pag.results.Codecs.given

/** `pag check` end to end (implementation_strategy.md §9, §11): analyze, then the
  * JVM run, then the judgment, with its exit codes. AnalyzeRefute's target is
  * unreachable; AnalyzeAlarm's is reached exactly when the input is positive.
  */
class CheckCommandSuite extends munit.FunSuite:

  def check(fixture: String, sources: Map[String, String], flags: String*): Pag.Result =
    Pag.withDomain("check", fixture, sources, flags*)

  def lastLine(r: Pag.Result): String = r.out.trim.linesIterator.toList.last

  // --- One row of the outcome table each

  test("refuted, and the run reaches it: UNSOUND, exit 3, naming the reaching run"):
    val r = check("AnalyzeAlarm", refutesAllStub, "--reach", "1", "--inputs", "5")
    assertEquals(r.exit, 3)
    assert(r.out.contains("analysis    REFUTED"), r.out)
    assert(r.out.contains("execution   REACHED-1"), r.out)
    assert(r.out.contains("UNSOUND — the domain refuted reach(1), but the program reaches it"), r.out)
    assert(lastLine(r).startsWith("reaching run") && lastLine(r).endsWith("inputs [5]"), r.out)

  test("refuted, and the run does not reach it: CONSISTENT, exit 0"):
    val r = check("AnalyzeRefute", intervalSources, "--reach", "1", "--inputs", "5")
    assertEquals((r.exit, r.err), (0, ""))
    assert(r.out.contains("execution   reach(1) not reached"), r.out)
    assertEquals(lastLine(r), "CONSISTENT — refuted, and this run does not reach reach(1)")

  test("an alarm, and the run reaches it: CONSISTENT, exit 0, the alarm is real"):
    val r = check("AnalyzeAlarm", intervalSources, "--reach", "1", "--inputs", "5")
    assertEquals((r.exit, r.err), (0, ""))
    assertEquals(lastLine(r), "CONSISTENT — an alarm, and this run reaches reach(1): the alarm is real")

  test("an alarm, and the run does not reach it: CONSISTENT, exit 0"):
    val r = check("AnalyzeAlarm", intervalSources, "--reach", "1", "--inputs", "-1")
    assertEquals(r.exit, 0)
    assertEquals(lastLine(r), "CONSISTENT — an alarm; this run does not reach reach(1)")

  test("inconclusive on a limit: NO VERDICT, exit 4, and the run still happens"):
    val r = check("AnalyzeAlarm", intervalSources, "--reach", "1", "--inputs", "5", "--iteration-limit", "1")
    assertEquals(r.exit, 4)
    assert(r.out.contains("execution   REACHED-1"), r.out)
    assert(lastLine(r).startsWith("NO VERDICT — the analysis was inconclusive (iteration limit 1)"), r.out)

  test("a domain failure: NO VERDICT, exit 5, stack trace on stderr, and the run still happens"):
    val r = check("AnalyzeAlarm", throwingStub, "--reach", "1", "--inputs", "5")
    assertEquals(r.exit, 5)
    assert(r.out.contains("analysis    DOMAIN FAILURE"), r.out)
    assert(r.out.contains("execution   REACHED-1"), r.out)
    assert(r.err.contains("at stub.Throws.transfer"), r.err)

  // --- The run

  test("the same domain is consistent on an input that misses the target"):
    val r = check("AnalyzeAlarm", refutesAllStub, "--reach", "1", "--inputs", "-1")
    assertEquals(r.exit, 0)
    assertEquals(lastLine(r), "CONSISTENT — refuted, and this run does not reach reach(1)")

  test("a run that exhausts its inputs is reported, and judges by its markers alone"):
    val r = check("AnalyzeAlarm", refutesAllStub, "--reach", "1")
    assertEquals(r.exit, 0)
    assert(r.out.contains("inputs [] ·") && r.out.contains("· exit 1"), r.out)

  // --- --json (implementation_strategy.md §13)

  def decoded(r: Pag.Result): CheckResult =
    Wire.decode[CheckResult](r.out.getBytes(UTF_8)).fold(e => fail(s"$e\n${r.out}"), identity)

  test("--json prints only the CheckResult, which decodes and says what the text says") {
    val r = check("AnalyzeAlarm", refutesAllStub, "--reach", "1", "--inputs", "5", "--json")
    assertEquals(r.exit, 3, "the exit codes do not change")
    assertEquals(r.out.linesIterator.size, 1, r.out)
    val c = decoded(r)
    assertEquals((c.query, c.inputs, c.outcome), (Reachable(1), List(BigInt(5)), Outcome.Unsound))
    assertEquals((c.analysis.verdict, c.run.reached, c.run.exitCode), (Verdict.Refuted, List(BigInt(1)), 0))
    assertEquals(c.envelope.profile, "bigint-main-v1")
    assert(c.envelope.commit.matches("[0-9a-f]{40}"), c.envelope.commit)
  }

  test("--json on a domain failure carries the error as data"):
    val c = decoded(check("AnalyzeAlarm", throwingStub, "--reach", "1", "--inputs", "5", "--json"))
    c.analysis.verdict match
      case Verdict.Inconclusive(Incomplete.DomainFailure("transfer", e)) =>
        assertEquals((e.className, e.message), ("java.lang.IllegalStateException", Some("boom")))
        assert(e.stackTrace.contains("at stub.Throws.transfer"), e.stackTrace)
      case other => fail(s"got $other")
    assertEquals((c.analysis.edges, c.analysis.uncertified), (None, None), "the certifier did not run")

  test("malformed inputs are a usage error, exit 1"):
    val r = check("AnalyzeAlarm", intervalSources, "--reach", "1", "--inputs", "five")
    assertEquals(r.exit, 1)
