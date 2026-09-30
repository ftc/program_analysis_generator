package pag.harness

import pag.core.Lowering
import pag.ir.*
import pag.probe.Inputs

/** The IR interpreter (implementation_strategy.md §9) on small lifted programs, with the
  * expected results worked out by hand.
  */
class IrInterpreterSuite extends munit.FunSuite:

  val Big: JType = JType.Ref("java.math.BigInteger")
  val Main: MethodId = MethodId("Probe", "main", List(JType.ArrayOf(JType.Ref("java.lang.String"))), JType.Void)
  val RandInt: MethodId = MethodId("pag.probe.Rand", "randInt", Nil, Big)
  val Reach: MethodId = MethodId("pag.probe.Reach", "reach", List(JType.Prim(PrimKind.Int)), JType.Void)

  val x: LVal.Local = LVal.Local("x", Big)
  val y: LVal.Local = LVal.Local("y", Big)
  val i: LVal.Local = LVal.Local("i", Big)
  def int(n: BigInt): RVal.IntConst = RVal.IntConst(n)
  def bin(l: RVal, op: BinOp, r: RVal): RVal = RVal.Binop(l, op, r)
  def input(t: LVal.Local): Cmd = Cmd.Assign(t, RVal.Invoke(InvokeKind.Static, RandInt, None, Nil))
  def reach(id: Int): Cmd = Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Static, Reach, None, List(int(id))))
  val ret: Cmd = Cmd.Return(None)

  def run(inputs: String, cmds: Cmd*)(using limit: Int = IrInterpreter.DefaultStepLimit): IrRun =
    val m = Method(Main, cmds.toVector, Vector.fill(cmds.size)(10))
    IrInterpreter.run(Lowering.lower(Program("Probe.java", List(m), Main)), Inputs.parse(inputs), limit)

  test("arithmetic is exact, beyond long"):
    // x = input; y = x * x - 1 + x
    val r = run("10000000000", input(x), Cmd.Assign(y, bin(bin(bin(x, BinOp.Mult, x), BinOp.Sub, int(1)), BinOp.Add, x)), ret)
    val big = BigInt("10000000000")
    assertEquals(r.values(y), big * big - 1 + big)
    assertEquals(r.ended, Ended.Exit)

  test("each comparison takes the branch it should"):
    // if x OP 5 goto 3 (skipping reach(1)); reach(1) at 2; reach(2) at 3
    val cases = List( // (op, x, taken?) where taken means the jump to 3
      (BinOp.Lt, 4, true), (BinOp.Lt, 5, false), (BinOp.Le, 5, true), (BinOp.Le, 6, false),
      (BinOp.Gt, 6, true), (BinOp.Gt, 5, false), (BinOp.Ge, 5, true), (BinOp.Ge, 4, false),
      (BinOp.Eq, 5, true), (BinOp.Eq, 6, false), (BinOp.Ne, 6, true), (BinOp.Ne, 5, false))
    for (op, value, taken) <- cases do
      val r = run(value.toString, input(x), Cmd.Goto(bin(x, op, int(5)), 3), reach(1), reach(2), ret)
      assertEquals(r.reached, if taken then Vector(BigInt(2)) else Vector(BigInt(1), BigInt(2)), s"x=$value $op 5")

  test("a loop runs its body the right number of times, and a reach inside repeats"):
    // i = 0; while (i < 3) { reach(1); i = i + 1 }; reach(2)
    val r = run("",
      Cmd.Assign(i, int(0)),                         // 0
      Cmd.Goto(bin(i, BinOp.Ge, int(3)), 5),         // 1
      reach(1),                                      // 2
      Cmd.Assign(i, bin(i, BinOp.Add, int(1))),      // 3
      Cmd.Goto(RVal.BoolConst(true), 1),             // 4
      reach(2),                                      // 5
      ret)                                           // 6
    assertEquals(r.reached, Vector[BigInt](1, 1, 1, 2))
    assertEquals(r.values(i), BigInt(3))

  test("inputs are used in order"):
    val r = run("7, -3", input(x), input(y), ret)
    assertEquals((r.values(x), r.values(y)), (BigInt(7), BigInt(-3)))

  test("running out of inputs ends the run where randInt was called, keeping reach ids before"):
    val r = run("1", reach(1), input(x), input(y), reach(2), ret)
    assertEquals(r.reached, Vector(BigInt(1)))
    assertEquals(r.ended, Ended.InputsExhausted(Loc.AppLoc(Main, 2, true)))

  test("an infinite loop stops at the step limit"):
    val r = run("", Cmd.Goto(RVal.BoolConst(true), 0), ret)(using 100)
    assertEquals(r.ended, Ended.StepLimit(100))

  test("a throw leaves no enabled edge: the run is stuck after it"):
    assertEquals(run("", Cmd.Throw, ret).ended, Ended.Stuck(Loc.AppLoc(Main, 0, false)))

  test("the visited locations are every location in order, entry to exit"):
    val r = run("", Cmd.Nop, ret)
    def pre(n: Int) = Loc.AppLoc(Main, n, true)
    def post(n: Int) = Loc.AppLoc(Main, n, false)
    assertEquals(r.visited, Vector(Loc.InternalMethodEntry(Main), pre(0), post(0), pre(1), post(1), Loc.InternalMethodExit(Main)))

  test("reading a local before it is assigned is an engine bug"):
    intercept[IllegalStateException](run("", Cmd.Assign(y, x), ret))
