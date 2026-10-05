package pag.core

import pag.ir.*

/** ControlFlowResolver (implementation_strategy.md §7) on lowered programs. The
  * loop-head guarantee is checked independently of the search that computes it:
  * with the heads removed, the part of the graph that can reach a seed has no
  * cycle left, which a topological sort in this file confirms.
  */
class ControlFlowResolverSuite extends munit.FunSuite:

  val Big: JType = JType.Ref("java.math.BigInteger")
  val Main: MethodId = MethodId("Probe", "main", List(JType.ArrayOf(JType.Ref("java.lang.String"))), JType.Void)
  val ReachM: MethodId = MethodId("pag.probe.Reach", "reach", List(JType.Prim(PrimKind.Int)), JType.Void)
  val i: LVal.Local = LVal.Local("i", Big)
  val j: LVal.Local = LVal.Local("j", Big)
  def int(n: Int): RVal.IntConst = RVal.IntConst(n)
  def reach(id: Int): Cmd = Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Static, ReachM, None, List(int(id))))
  def jump(to: Int): Cmd = Cmd.Goto(RVal.BoolConst(true), to)
  def ifGe(v: LVal.Local, n: Int, to: Int): Cmd = Cmd.Goto(RVal.Binop(v, BinOp.Ge, int(n)), to)
  def inc(v: LVal.Local): Cmd = Cmd.Assign(v, RVal.Binop(v, BinOp.Add, int(1)))
  val ret: Cmd = Cmd.Return(None)
  def pre(k: Int): Loc = Loc.AppLoc(Main, k, true)
  def post(k: Int): Loc = Loc.AppLoc(Main, k, false)

  def lower(cmds: Cmd*): Lowered =
    Lowering.lower(Program("Probe.java", List(Method(Main, cmds.toVector, Vector.fill(cmds.size)(10))), Main))

  def seedOf(l: Lowered, id: Int): Set[Loc] = l.reachSites(BigInt(id)).toSet

  /** Locations from which some seed can be reached. */
  def reachingSeeds(cfg: Cfg, seeds: Set[Loc]): Set[Loc] =
    def grow(found: Set[Loc]): Set[Loc] =
      val more = found ++ cfg.transitions.filter(t => found(t.to)).map(_.from)
      if more == found then found else grow(more)
    grow(seeds)

  /** Kahn's algorithm: true when the graph on `nodes`, minus `removed`, has no cycle. */
  def acyclicWithout(cfg: Cfg, nodes: Set[Loc], removed: Set[Loc]): Boolean =
    val keep = nodes -- removed
    val edges = cfg.transitions.filter(t => keep(t.from) && keep(t.to)).map(t => t.from -> t.to)
    def peel(left: Set[Loc], es: List[(Loc, Loc)]): Boolean =
      val sources = left.filter(n => !es.exists(_._2 == n))
      if left.isEmpty then true
      else if sources.isEmpty then false
      else peel(left -- sources, es.filterNot(e => sources(e._1)))
    peel(keep, edges)

  def assertEveryLoopHasAHead(l: Lowered, seeds: Set[Loc], heads: Set[Loc]): Unit =
    assert(acyclicWithout(l.cfg, reachingSeeds(l.cfg, seeds), heads), s"a cycle avoids every head in $heads")

  // --- into and outOf

  test("into and outOf: the transitions ending at and leaving a location"):
    // if i >= 0 goto 2; nop; return
    val l = lower(ifGe(i, 0, 2), Cmd.Nop, ret)
    val r = ControlFlowResolver(l.cfg)
    assertEquals(r.outOf(post(0)).map(_.to).toSet, Set(pre(1), pre(2)))
    assertEquals(r.into(pre(2)).map(_.from).toSet, Set(post(0), post(1)))
    assertEquals(r.into(l.cfg.init), Nil)
    assertEquals(r.outOf(l.cfg.exit), Nil)

  test("into and outOf partition the transitions"):
    val l = lower(ifGe(i, 0, 2), Cmd.Nop, ret)
    val r = ControlFlowResolver(l.cfg)
    val locs = l.cfg.transitions.flatMap(t => List(t.from, t.to)).toSet
    assertEquals(locs.toList.flatMap(r.into).sortBy(_.toString), l.cfg.transitions.sortBy(_.toString))
    assertEquals(locs.toList.flatMap(r.outOf).sortBy(_.toString), l.cfg.transitions.sortBy(_.toString))

  // --- loop heads

  test("straight-line code has no loop heads"):
    val l = lower(Cmd.Assign(i, int(0)), inc(i), reach(1), ret)
    assertEquals(ControlFlowResolver(l.cfg).loopHeads(seedOf(l, 1)), Set.empty[Loc])

  test("a branch that rejoins is not a loop"):
    val l = lower(ifGe(i, 0, 3), inc(i), jump(4), inc(j), reach(1), ret)
    assertEquals(ControlFlowResolver(l.cfg).loopHeads(seedOf(l, 1)), Set.empty[Loc])

  test("a while loop before the target gets exactly one head, inside the loop"):
    // 0 i = 0; 1 if i >= 3 goto 4; 2 i = i + 1; 3 goto 1; 4 reach(1); 5 return
    val l = lower(Cmd.Assign(i, int(0)), ifGe(i, 3, 4), inc(i), jump(1), reach(1), ret)
    val seeds = seedOf(l, 1)
    val heads = ControlFlowResolver(l.cfg).loopHeads(seeds)
    val loop = Set(pre(1), post(1), pre(2), post(2), pre(3), post(3))
    assertEquals(heads.size, 1)
    assert(heads.subsetOf(loop), heads)
    assertEveryLoopHasAHead(l, seeds, heads)

  test("a target inside the loop: the loop still gets a head"):
    // 0 i = 0; 1 if i >= 3 goto 5; 2 reach(1); 3 i = i + 1; 4 goto 1; 5 return
    val l = lower(Cmd.Assign(i, int(0)), ifGe(i, 3, 5), reach(1), inc(i), jump(1), ret)
    val seeds = seedOf(l, 1)
    val heads = ControlFlowResolver(l.cfg).loopHeads(seeds)
    assert(heads.nonEmpty)
    assertEveryLoopHasAHead(l, seeds, heads)

  test("nested loops: every cycle has a head, and there are at least two"):
    // 0 i = 0
    // 1 if i >= 3 goto 8        outer
    // 2 j = 0
    // 3 if j >= 0 goto 6        inner (only the shape matters here)
    // 4 j = j + 1
    // 5 goto 3
    // 6 i = i + 1
    // 7 goto 1
    // 8 reach(1); 9 return
    val l = lower(Cmd.Assign(i, int(0)), ifGe(i, 3, 8), Cmd.Assign(j, int(0)), ifGe(j, 0, 6), inc(j), jump(3),
      inc(i), jump(1), reach(1), ret)
    val seeds = seedOf(l, 1)
    val heads = ControlFlowResolver(l.cfg).loopHeads(seeds)
    assert(heads.size >= 2, heads)
    assertEveryLoopHasAHead(l, seeds, heads)

  test("a jump to itself is a loop with a head"):
    // 0 if i >= 0 goto 2; 1 goto 1; 2 reach(1); 3 return. The self-loop at 1 never leads to the
    // target, so the search is seeded at the loop itself.
    val l = lower(ifGe(i, 0, 2), jump(1), reach(1), ret)
    val heads = ControlFlowResolver(l.cfg).loopHeads(Set(pre(1)))
    assertEquals(heads.size, 1)
    assertEveryLoopHasAHead(l, Set(pre(1)), heads)

  test("a loop that cannot lead to a seed gets no head: the backward search never meets it"):
    // 0 reach(1); 1 i = 0; 2 if i >= 3 goto 5; 3 i = i + 1; 4 goto 2; 5 return
    val l = lower(reach(1), Cmd.Assign(i, int(0)), ifGe(i, 3, 5), inc(i), jump(2), ret)
    assertEquals(ControlFlowResolver(l.cfg).loopHeads(seedOf(l, 1)), Set.empty[Loc])

  test("several seeds: loops before either one get heads"):
    // 0 if i >= 3 goto 3; 1 i = i + 1; 2 goto 0; 3 reach(1); 4 if j >= 3 goto 7; 5 j = j + 1; 6 goto 4; 7 reach(2); 8 return
    val l = lower(ifGe(i, 3, 3), inc(i), jump(0), reach(1), ifGe(j, 3, 7), inc(j), jump(4), reach(2), ret)
    val seeds = seedOf(l, 1) ++ seedOf(l, 2)
    val heads = ControlFlowResolver(l.cfg).loopHeads(seeds)
    assertEquals(heads.size, 2)
    assertEveryLoopHasAHead(l, seeds, heads)

  test("the same input gives the same heads"):
    val l = lower(Cmd.Assign(i, int(0)), ifGe(i, 3, 8), Cmd.Assign(j, int(0)), ifGe(j, 0, 6), inc(j), jump(3),
      inc(i), jump(1), reach(1), ret)
    val seeds = seedOf(l, 1)
    assertEquals(ControlFlowResolver(l.cfg).loopHeads(seeds), ControlFlowResolver(l.cfg).loopHeads(seeds))

  test("the check itself is not vacuous: with no heads removed, a loop is a cycle"):
    val l = lower(Cmd.Assign(i, int(0)), ifGe(i, 3, 4), inc(i), jump(1), reach(1), ret)
    assert(!acyclicWithout(l.cfg, reachingSeeds(l.cfg, seedOf(l, 1)), Set.empty))
