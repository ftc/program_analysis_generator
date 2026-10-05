package pag.core

import pag.api
import pag.ir.*
import pag.core.TestDomains.*

/** Compute then certify (implementation_strategy.md §7), and Phase 4's
  * done-when: certification is independent of the worklist and the resolver.
  */
class AnalysisSuite extends munit.FunSuite:

  import Flag.{Maybe, No}

  // 0 —skip→ 1 —assume(1 < 2)→ 2 —skip→ 3 (target); a loop 2 —x := 2→ 1; a dead branch 1 —assume(1 > 2)→ 4 —skip→ 3
  val reachable: Cfg =
    cfg(skip(0, 1), guardEdge(1, 1, BinOp.Lt, 2, 2), skip(2, 3), assign(2, 1), guardEdge(1, 1, BinOp.Gt, 2, 4), skip(4, 3))
  // the same, with the live branch's condition false too
  val unreachable: Cfg =
    cfg(skip(0, 1), guardEdge(1, 1, BinOp.Ge, 2, 2), skip(2, 3), assign(2, 1), guardEdge(1, 1, BinOp.Gt, 2, 4), skip(4, 3))
  val target: Set[Loc] = Set(L(3))
  val locations: List[Loc] = List(0, 1, 2, 3, 4).map(L)

  /** `g` with reach(1) at L(3). */
  def lowered(g: Cfg): Lowered = Lowered(g, Map(BigInt(1) -> List(L(3))))

  def analyze[S](d: pag.api.Domain[S], g: Cfg, limits: Limits = Limits(),
      resolver: Option[ControlFlowResolver] = None): AnalysisResult[S] =
    Analysis.analyze(d, lowered(g), Reachable(1), limits, resolver = resolver).fold(e => fail(e), identity)

  // --- Verdicts

  test("an unreachable target is Refuted, with all checks recorded") {
    val r = analyze(FlagDomain(), unreachable)
    assertEquals(r.verdict, Verdict.Refuted)
    assertEquals(r.certification.map(_.edges), Some(6))
  }

  test("the result carries the map the certifier checked"):
    val r = analyze(FlagDomain(), unreachable)
    val computed = Worklist.compute(FlagDomain(), ControlFlowResolver(unreachable), target)
    assertEquals(r.states, computed.states)
    // by hand: 2 and 4 reach 3 by skips, so both are Maybe; the false assumes into 2 and 4 stop at 1
    assertEquals(r.states, Map[Loc, Flag](L(2) -> Maybe, L(3) -> Maybe, L(4) -> Maybe))
    assert(Certifier.certify(FlagDomain(), unreachable, target, r.states).toOption.exists(_.refutes))

  test("an early stop keeps the partial map"):
    val r = analyze(FlagDomain(), reachable, Limits(iterations = 1))
    assertEquals(r.verdict, Verdict.Inconclusive(Incomplete.IterationLimit(1)))
    assertEquals(r.states, Map[Loc, Flag](L(3) -> Maybe, L(2) -> Maybe)) // the target, plus one step back

  test("toString prints the verdict and the map in program order"):
    val r = analyze(FlagDomain(), reachable)
    assertEquals(r.toString.linesIterator.toList, List(
      s"Alarm · ${r.iterations} iterations · absent locations are ⊥",
      "  pre(0)    Maybe", "  pre(1)    Maybe", "  pre(2)    Maybe", "  pre(3)    Maybe", "  pre(4)    Maybe"))

  test("a reachable target is an Alarm"):
    assertEquals(analyze(FlagDomain(), reachable).verdict, Verdict.Alarm)

  test("a domain that throws in transfer: Inconclusive(DomainFailure), and no certification"):
    val boom = RuntimeException("boom")
    val d = new FlagDomain { override def transfer(s: api.Step, p: Flag) = throw boom }
    val r = analyze(d, unreachable)
    assertEquals((r.verdict, r.certification), (Verdict.Inconclusive(Incomplete.DomainFailure("transfer", boom)), None))

  test("a domain that fails only during certification is Inconclusive too, never Alarm"):
    // the worklist never calls isBottom; the certifier does, for [refute]
    val boom = RuntimeException("boom")
    val d = new FlagDomain { override def isBottom(s: Flag) = throw boom }
    assertEquals(analyze(d, unreachable).verdict,
      Verdict.Inconclusive(Incomplete.DomainFailure("isBottom", boom)))

  test("a search stopped by the iteration limit is Inconclusive, and is not certified"):
    val r = analyze(FlagDomain(), unreachable, Limits(iterations = 2))
    assertEquals((r.verdict, r.certification), (Verdict.Inconclusive(Incomplete.IterationLimit(2)), None))

  // --- The query

  test("the targets are the query's: two reach sites in one program get their own verdicts"):
    // reach(1) at L(3), behind a false assume; reach(2) at L(5), behind a true one
    val g = cfg(skip(0, 1), guardEdge(1, 1, BinOp.Gt, 2, 3), guardEdge(1, 1, BinOp.Lt, 2, 5))
    val l = Lowered(g, Map(BigInt(1) -> List(L(3)), BigInt(2) -> List(L(5))))
    assertEquals(Analysis.analyze(FlagDomain(), l, Reachable(1)).map(_.verdict), Right(Verdict.Refuted))
    assertEquals(Analysis.analyze(FlagDomain(), l, Reachable(2)).map(_.verdict), Right(Verdict.Alarm))

  test("a query the program cannot answer is Left, and nothing runs"):
    val noDomain = new FlagDomain { override def top = throw AssertionError("the domain must not be called") }
    assertEquals(Analysis.analyze(noDomain, lowered(reachable), Reachable(7)).map(_.verdict),
      Left("no reach(7) call; the program has reach(1)"))

  // --- Certification is independent (Phase 4 done-when)

  test("no map at all certifies a reachable target: every assignment of ⊥/⊤ to every location"):
    // A corrupted worklist can hand the certifier any map; with a sound domain, none refutes.
    val maps = locations.foldLeft(List(Map.empty[Loc, Flag]))((ms, l) => ms.flatMap(m => List(m + (l -> No), m + (l -> Maybe))))
    assertEquals(maps.size, 32)
    val refuting = maps.filter(m => Certifier.certify(FlagDomain(), reachable, target, m).toOption.exists(_.refutes))
    assertEquals(refuting, Nil)

  test("the same enumeration is not vacuous: for the unreachable target, some map certifies"):
    val maps = locations.foldLeft(List(Map.empty[Loc, Flag]))((ms, l) => ms.flatMap(m => List(m + (l -> No), m + (l -> Maybe))))
    assert(maps.exists(m => Certifier.certify(FlagDomain(), unreachable, target, m).toOption.exists(_.refutes)))

  test("a broken resolver — no loop heads, predecessors dropped — cannot produce Refuted"):
    // It drops every transition into L(1), so the worklist never reaches init and leaves it ⊥:
    // the computed map looks like a refutation. The certifier checks cfg.transitions itself.
    class Broken(g: Cfg) extends ControlFlowResolver(g):
      override def loopHeads(seeds: Set[Loc]): Set[Loc] = Set.empty
      override def into(loc: Loc): List[Transition] = if loc == L(1) then Nil else super.into(loc)
    val r = analyze(FlagDomain(), reachable, resolver = Some(Broken(reachable)))
    assertEquals(r.verdict, Verdict.Alarm)
    val c = r.certification.get
    assert(c.initBottom, "the broken worklist did leave init at ⊥")
    assert(c.uncertified.nonEmpty, "and the certifier found the edges it skipped")

  test("a broken resolver on an unreachable target costs at most the proof, never soundness"):
    class Broken(g: Cfg) extends ControlFlowResolver(g):
      override def loopHeads(seeds: Set[Loc]): Set[Loc] = Set.empty
      override def into(loc: Loc): List[Transition] = if loc == L(1) then Nil else super.into(loc)
    val v = analyze(FlagDomain(), unreachable, resolver = Some(Broken(unreachable))).verdict
    assert(v == Verdict.Refuted || v == Verdict.Alarm, v)
