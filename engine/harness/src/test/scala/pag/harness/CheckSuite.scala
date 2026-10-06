package pag.harness

import pag.results.{ErrorInfo, Incomplete, Outcome, Reachable, Verdict}

/** The reachability check's judgment (implementation_strategy.md §9): one test
  * per row of the outcome table.
  */
class CheckSuite extends munit.FunSuite:

  val q: Reachable = Reachable(1)
  val limit: Incomplete = Incomplete.IterationLimit(10)
  val failure: Incomplete = Incomplete.DomainFailure("transfer", ErrorInfo.of(RuntimeException("boom")))

  test("Refuted, and the run reaches the target: Unsound"):
    assertEquals(Check.judge(Verdict.Refuted, Vector(BigInt(1)), q), Outcome.Unsound)

  test("Refuted, and the run does not reach it: Consistent"):
    assertEquals(Check.judge(Verdict.Refuted, Vector.empty, q), Outcome.Consistent)

  test("Alarm, and the run reaches it: Consistent — the alarm is real"):
    assertEquals(Check.judge(Verdict.Alarm, Vector(BigInt(1)), q), Outcome.Consistent)

  test("Alarm, and the run does not reach it: Consistent"):
    assertEquals(Check.judge(Verdict.Alarm, Vector.empty, q), Outcome.Consistent)

  test("Inconclusive on a limit: NoVerdict, whether or not the run reaches it"):
    assertEquals(Check.judge(Verdict.Inconclusive(limit), Vector(BigInt(1)), q), Outcome.NoVerdict(limit))
    assertEquals(Check.judge(Verdict.Inconclusive(limit), Vector.empty, q), Outcome.NoVerdict(limit))

  test("Inconclusive on a domain failure: NoVerdict, whether or not the run reaches it"):
    assertEquals(Check.judge(Verdict.Inconclusive(failure), Vector(BigInt(1)), q), Outcome.NoVerdict(failure))
    assertEquals(Check.judge(Verdict.Inconclusive(failure), Vector.empty, q), Outcome.NoVerdict(failure))

  test("only the queried id counts: reaching a different marker contradicts nothing"):
    assertEquals(Check.judge(Verdict.Refuted, Vector(BigInt(2), BigInt(3)), q), Outcome.Consistent)

  test("the queried id anywhere in the run counts, however often and in whatever order"):
    assertEquals(Check.judge(Verdict.Refuted, Vector(BigInt(2), BigInt(1), BigInt(1)), q), Outcome.Unsound)

  test("ids are compared by value, including negative ones"):
    assertEquals(Check.judge(Verdict.Refuted, Vector(BigInt(-4)), Reachable(-4)), Outcome.Unsound)
    assertEquals(Check.judge(Verdict.Refuted, Vector(BigInt(4)), Reachable(-4)), Outcome.Consistent)
