package pag.core

import pag.ir.*

/** Lowering (implementation_strategy.md §5.3): one test per row of the table,
  * with the expected transitions written out by hand.
  */
class LoweringSuite extends munit.FunSuite:

  val Big: JType = JType.Ref("java.math.BigInteger")
  val StringArray: JType = JType.ArrayOf(JType.Ref("java.lang.String"))
  val Main: MethodId = MethodId("Probe", "main", List(StringArray), JType.Void)
  val RandInt: MethodId = MethodId("pag.probe.Rand", "randInt", Nil, Big)
  val Reach: MethodId = MethodId("pag.probe.Reach", "reach", List(JType.Prim(PrimKind.Int)), JType.Void)
  val Add: MethodId = MethodId("java.math.BigInteger", "add", List(Big), Big)

  val x: LVal.Local = LVal.Local("x", Big)
  val y: LVal.Local = LVal.Local("y", Big)
  val entry: Loc = Loc.InternalMethodEntry(Main)
  val exit: Loc = Loc.InternalMethodExit(Main)
  def pre(i: Int): Loc.AppLoc = Loc.AppLoc(Main, i, true)
  def post(i: Int): Loc.AppLoc = Loc.AppLoc(Main, i, false)
  def int(n: Int): RVal.IntConst = RVal.IntConst(n)
  val ret: Cmd = Cmd.Return(None)

  def lower(cmds: Cmd*): Lowered =
    Lowering.lower(Program("Probe.java", List(Method(Main, cmds.toVector, Vector.fill(cmds.size)(10))), Main))

  /** The transitions for command 0 of a program whose command 1 is a return. */
  def edgesOf(first: Cmd): Set[Transition] =
    lower(first, ret).cfg.transitions.toSet
      .filter(t => t.from == pre(0) || t.from == post(0))

  // --- One row of the table each

  test("method entry: entry —skip→ pre(0); init and exit are the method's entry and exit"):
    val cfg = lower(ret).cfg
    assert(cfg.transitions.contains(Transition(entry, Step.Skip, pre(0))))
    assertEquals((cfg.init, cfg.exit), (entry, exit))

  test("x := e: pre —x := e→ post —skip→ next"):
    val e = RVal.Binop(x, BinOp.Add, int(1))
    assertEquals(edgesOf(Cmd.Assign(y, e)),
      Set(Transition(pre(0), Step.Assign(y, e), post(0)), Transition(post(0), Step.Skip, pre(1))))

  test("x := f(args): a Call with the receiver as first argument"):
    val call = RVal.Invoke(InvokeKind.Virtual, Add, Some(x), List(y))
    assertEquals(edgesOf(Cmd.Assign(y, call)),
      Set(Transition(pre(0), Step.Call(Some(y), Add, List(x, y)), post(0)), Transition(post(0), Step.Skip, pre(1))))

  test("x := randInt(): a Call with no arguments"):
    val call = RVal.Invoke(InvokeKind.Static, RandInt, None, Nil)
    assert(edgesOf(Cmd.Assign(x, call)).contains(Transition(pre(0), Step.Call(Some(x), RandInt, Nil), post(0))))

  test("a call statement: a Call with no target"):
    val call = RVal.Invoke(InvokeKind.Virtual, Add, Some(x), List(y))
    assert(edgesOf(Cmd.InvokeStmt(call)).contains(Transition(pre(0), Step.Call(None, Add, List(x, y)), post(0))))

  test("reach(id): a skip, and pre is recorded as reach id's site"):
    val lowered = lower(Cmd.Nop, Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Static, Reach, None, List(int(7)))), ret)
    assert(lowered.cfg.transitions.contains(Transition(pre(1), Step.Skip, post(1))))
    assert(!lowered.cfg.transitions.exists(_.step.isInstanceOf[Step.Call]), "reach must not reach a domain")
    assertEquals(lowered.reachSites, Map(BigInt(7) -> pre(1)))

  test("main's args binding: a skip"):
    val bind = Cmd.Assign(LVal.Local("args", StringArray), LVal.Param(0, StringArray))
    assertEquals(edgesOf(bind), Set(Transition(pre(0), Step.Skip, post(0)), Transition(post(0), Step.Skip, pre(1))))

  test("nop: a skip"):
    assertEquals(edgesOf(Cmd.Nop), Set(Transition(pre(0), Step.Skip, post(0)), Transition(post(0), Step.Skip, pre(1))))

  test("goto(true, t): skip to post, then skip to pre(t)"):
    val edges = lower(Cmd.Nop, Cmd.Goto(RVal.BoolConst(true), 0), ret).cfg.transitions.toSet
    assert(edges.contains(Transition(pre(1), Step.Skip, post(1))))
    assert(edges.contains(Transition(post(1), Step.Skip, pre(0))))
    assert(!edges.exists(e => e.from == post(1) && e.to == pre(2)), "an unconditional jump does not fall through")

  test("goto(c, t): from post, assume c to pre(t) and assume ¬c to pre(i+1)"):
    val cond = RVal.Binop(x, BinOp.Lt, y)
    assertEquals(lower(Cmd.Goto(cond, 2), Cmd.Nop, ret).cfg.transitions.toSet.filter(_.from == post(0)), Set(
      Transition(post(0), Step.Assume(cond), pre(2)),
      Transition(post(0), Step.Assume(RVal.Binop(x, BinOp.Ge, y)), pre(1))))

  test("¬ flips each of the six comparisons and keeps the operands"):
    val flips = Map(BinOp.Lt -> BinOp.Ge, BinOp.Ge -> BinOp.Lt, BinOp.Le -> BinOp.Gt,
      BinOp.Gt -> BinOp.Le, BinOp.Eq -> BinOp.Ne, BinOp.Ne -> BinOp.Eq)
    for (op, flipped) <- flips do
      val edges = lower(Cmd.Goto(RVal.Binop(x, op, y), 0), ret).cfg.transitions
      assert(edges.contains(Transition(post(0), Step.Assume(RVal.Binop(x, flipped, y)), pre(1))), s"$op")

  test("return: skip to post, then skip to the method's exit"):
    assertEquals(edgesOf(ret).filter(_.from == post(0)), Set(Transition(post(0), Step.Skip, exit)))

  // --- Beyond the table: only reachable under enforce = false

  test("throw: skip to post, and nothing after, since execution ends"):
    val edges = lower(Cmd.Throw, ret).cfg.transitions
    assert(edges.contains(Transition(pre(0), Step.Skip, post(0))))
    assert(!edges.exists(_.from == post(0)))

  test("a branch whose condition is not a comparison fails loudly"):
    intercept[IllegalStateException](lower(Cmd.Goto(RVal.Binop(x, BinOp.Add, y), 0), ret))

  test("falling through past the last command fails loudly"):
    intercept[IllegalStateException](lower(Cmd.Nop))

  // --- The whole relation

  test("every command's pre has exactly one edge, to its own post"):
    val cmds = Vector(Cmd.Assign(x, RVal.Invoke(InvokeKind.Static, RandInt, None, Nil)),
      Cmd.Goto(RVal.Binop(x, BinOp.Lt, int(0)), 3), Cmd.Goto(RVal.BoolConst(true), 4), Cmd.Nop, ret)
    val edges = lower(cmds*).cfg.transitions
    for i <- cmds.indices do
      assertEquals(edges.filter(_.from == pre(i)).map(_.to), List(post(i)), s"command $i")
    // entry edge + one effect edge each + successors: 1 + 5 + (1 + 2 + 1 + 1 + 1)
    assertEquals(edges.size, 12)
