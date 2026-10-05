package pag.core

import pag.ir.*

/** Query resolution (implementation_strategy.md §6): `Reachable(id)` names the
  * `pre` location of the `reach(id)` call, and nothing else.
  */
class QuerySuite extends munit.FunSuite:

  val Big: JType = JType.Ref("java.math.BigInteger")
  val Main: MethodId = MethodId("Probe", "main", List(JType.ArrayOf(JType.Ref("java.lang.String"))), JType.Void)
  val ReachM: MethodId = MethodId("pag.probe.Reach", "reach", List(JType.Prim(PrimKind.Int)), JType.Void)
  def reach(id: Int): Cmd = Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Static, ReachM, None, List(RVal.IntConst(id))))
  val x: LVal.Local = LVal.Local("x", Big)
  def pre(i: Int): Loc = Loc.AppLoc(Main, i, true)

  def lower(cmds: Cmd*): Lowered =
    Lowering.lower(Program("Probe.java", List(Method(Main, cmds.toVector, Vector.fill(cmds.size)(10))), Main))

  // x = 0; reach(1); x = 1; reach(2); reach(-3); return
  val program: Lowered = lower(
    Cmd.Assign(x, RVal.IntConst(0)),
    reach(1),
    Cmd.Assign(x, RVal.IntConst(1)),
    reach(2),
    reach(-3),
    Cmd.Return(None)
  )

  test("each id resolves to the pre location of its own reach call"):
    val stringOrLocs = Query.resolve(Reachable(1), program)
    assertEquals(stringOrLocs, Right(Set(pre(1))))
    assertEquals(Query.resolve(Reachable(2), program), Right(Set(pre(3))))

  test("a negative id resolves like any other"):
    assertEquals(Query.resolve(Reachable(-3), program), Right(Set(pre(4))))

  test("the resolved location is in the Cfg: a transition leaves it"):
    val Right(locs) = Query.resolve(Reachable(2), program): @unchecked
    assert(locs.forall(l => program.cfg.transitions.exists(_.from == l)))

  test("an unknown id is an error that names the ids the program has"):
    assertEquals(
      Query.resolve(Reachable(7), program),
      Left("no reach(7) call; the program has reach(-3, 1, 2)")
    )

  test("two reach calls sharing an id are an error that names both sites"):
    // outside the profile (LiteralUnique): the backstop for when the check did not run
    val dup = lower(reach(7), Cmd.Assign(x, RVal.IntConst(0)), reach(7), Cmd.Return(None))
    assertEquals(
      Query.resolve(Reachable(7), dup),
      Left("reach(7) appears 2 times, at pre(0), pre(2); reach ids must be unique (§5.2)")
    )

  test("a program with no reach calls is an error that says so"):
    assertEquals(
      Query.resolve(Reachable(1), lower(Cmd.Return(None))),
      Left("no reach(1) call: the program has no reach calls")
    )

  test("an id beyond the Int range never matches: reach takes an int"):
    // reachSites is keyed by BigInt; Reachable(id) widens, never truncates
    val big = lower(Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Static, ReachM, None, List(RVal.IntConst(BigInt(1) << 32)))),
      Cmd.Return(None))
    assert(Query.resolve(Reachable(0), big).isLeft)
