package pag.core

import pag.ir.*

/** Lifting (implementation_strategy.md §5.7): one test per row of the table,
  * with expected results written out by hand.
  */
class LiftingSuite extends munit.FunSuite:

  val Big: JType = JType.Ref("java.math.BigInteger")
  val Main: MethodId = MethodId("Probe", "main", List(JType.ArrayOf(JType.Ref("java.lang.String"))), JType.Void)
  def bigint(name: String, params: List[JType], ret: JType = Big): MethodId =
    MethodId("java.math.BigInteger", name, params, ret)
  val ValueOf: MethodId = bigint("valueOf", List(JType.Prim(PrimKind.Long)))
  val CompareTo: MethodId = bigint("compareTo", List(Big), JType.Prim(PrimKind.Int))
  val EqualsM: MethodId = bigint("equals", List(JType.Ref("java.lang.Object")), JType.Prim(PrimKind.Boolean))
  val RandInt: MethodId = MethodId("pag.probe.Rand", "randInt", Nil, Big)
  val Reach: MethodId = MethodId("pag.probe.Reach", "reach", List(JType.Prim(PrimKind.Int)), JType.Void)

  val x: LVal.Local = LVal.Local("x", Big)
  val y: LVal.Local = LVal.Local("y", Big)
  val r: LVal.Local = LVal.Local("$stack2", Big)
  val c: LVal.Local = LVal.Local("$stack3", JType.Prim(PrimKind.Int))
  val z: LVal.Local = LVal.Local("$stack4", JType.Prim(PrimKind.Boolean))

  def virtual(m: MethodId, recv: RVal, args: RVal*): RVal.Invoke =
    RVal.Invoke(InvokeKind.Virtual, m, Some(recv), args.toList)
  def static(m: MethodId, args: RVal*): RVal.Invoke = RVal.Invoke(InvokeKind.Static, m, None, args.toList)
  def int(n: Int): RVal.IntConst = RVal.IntConst(n)

  def lift(cmds: Cmd*)(using mode: Lifting.Mode = Lifting.Mode.Strict): Vector[Cmd] =
    val m = Method(Main, cmds.toVector, Vector.tabulate(cmds.size)(10 + _))
    Lifting.lift(Program("Probe.java", List(m), Main), mode).methods.head.body

  // --- One row of the table each

  test("valueOf(n) becomes n"):
    assertEquals(lift(Cmd.Assign(r, static(ValueOf, int(3)))), Vector(Cmd.Assign(r, int(3))))

  test("ZERO, ONE, TWO, TEN become 0, 1, 2, 10"):
    for (field, n) <- List("ZERO" -> 0, "ONE" -> 1, "TWO" -> 2, "TEN" -> 10) do
      assertEquals(lift(Cmd.Assign(r, LVal.StaticField("java.math.BigInteger", field))), Vector(Cmd.Assign(r, int(n))))

  test("add, subtract, multiply become +, -, *"):
    for (name, op) <- List("add" -> BinOp.Add, "subtract" -> BinOp.Sub, "multiply" -> BinOp.Mult) do
      assertEquals(lift(Cmd.Assign(y, virtual(bigint(name, List(Big)), x, r))),
        Vector(Cmd.Assign(y, RVal.Binop(x, op, r))))

  test("negate becomes 0 - a"):
    assertEquals(lift(Cmd.Assign(y, virtual(bigint("negate", Nil), x))),
      Vector(Cmd.Assign(y, RVal.Binop(int(0), BinOp.Sub, x))))

  test("a compareTo temp and its if fuse: nop, then if a OP b, operator unchanged"):
    for op <- BinOp.values.filter(o => o != BinOp.Add && o != BinOp.Sub && o != BinOp.Mult) do
      assertEquals(lift(Cmd.Assign(c, virtual(CompareTo, x, y)), Cmd.Goto(RVal.Binop(c, op, int(0)), 1)),
        Vector(Cmd.Nop, Cmd.Goto(RVal.Binop(x, op, y), 1)))

  test("an equals temp tested == false fuses to if a != b"):
    assertEquals(lift(Cmd.Assign(z, virtual(EqualsM, x, y)), Cmd.Goto(RVal.Binop(z, BinOp.Eq, RVal.BoolConst(false)), 1)),
      Vector(Cmd.Nop, Cmd.Goto(RVal.Binop(x, BinOp.Ne, y), 1)))

  test("an equals temp tested != false fuses to if a == b"):
    assertEquals(lift(Cmd.Assign(z, virtual(EqualsM, x, y)), Cmd.Goto(RVal.Binop(z, BinOp.Ne, RVal.BoolConst(false)), 1)),
      Vector(Cmd.Nop, Cmd.Goto(RVal.Binop(x, BinOp.Eq, y), 1)))

  test("randInt and reach are unchanged"):
    val cmds = Vector(Cmd.Assign(x, static(RandInt)), Cmd.InvokeStmt(static(Reach, int(1))))
    assertEquals(lift(cmds*), cmds)

  // --- A whole method, and what lifting must preserve

  test("a whole method lifts as worked out by hand, one command for one"):
    // x = randInt(); y = x.add(ONE); if (y.compareTo(valueOf(5)) > 0) reach(1); return
    val source = Vector(
      Cmd.Assign(x, static(RandInt)),
      Cmd.Assign(r, LVal.StaticField("java.math.BigInteger", "ONE")),
      Cmd.Assign(y, virtual(bigint("add", List(Big)), x, r)),
      Cmd.Assign(r, static(ValueOf, int(5))),
      Cmd.Assign(c, virtual(CompareTo, y, r)),
      Cmd.Goto(RVal.Binop(c, BinOp.Le, int(0)), 7),
      Cmd.InvokeStmt(static(Reach, int(1))),
      Cmd.Return(None))
    // Constant substitution (§5.7) puts 1 and 5 into their uses; the assignments stay.
    assertEquals(lift(source*), Vector(
      Cmd.Assign(x, static(RandInt)),
      Cmd.Assign(r, int(1)),
      Cmd.Assign(y, RVal.Binop(x, BinOp.Add, int(1))),
      Cmd.Assign(r, int(5)),
      Cmd.Nop,
      Cmd.Goto(RVal.Binop(y, BinOp.Le, int(5)), 7),
      Cmd.InvokeStmt(static(Reach, int(1))),
      Cmd.Return(None)))

  test("lines are untouched"):
    val m = Method(Main, Vector(Cmd.Assign(c, virtual(CompareTo, x, y)), Cmd.Goto(RVal.Binop(c, BinOp.Lt, int(0)), 0)),
      Vector(12, 12))
    assertEquals(Lifting.lift(Program("Probe.java", List(m), Main), Lifting.Mode.Strict).methods.head.lines, Vector(12, 12))

  // --- Modes

  val misshapen: Seq[Cmd] = Seq(Cmd.Assign(c, virtual(CompareTo, x, y)), Cmd.Nop, Cmd.Goto(RVal.Binop(c, BinOp.Lt, int(0)), 0))

  test("strict: a compare temp not tested by the next if is a bug, and fails loudly"):
    intercept[IllegalStateException](lift(misshapen*))

  test("lenient: a compare temp not tested by the next if is left as it is"):
    assertEquals(lift(misshapen*)(using Lifting.Mode.Lenient), misshapen.toVector)

  // --- Constant substitution (§5.7), within a basic block

  val ret: Cmd = Cmd.Return(None)
  def gt(l: RVal, r: RVal): RVal = RVal.Binop(l, BinOp.Gt, r)

  test("a constant reaches its use in the same block; the assignment stays"):
    assertEquals(lift(Cmd.Assign(r, int(10)), Cmd.Goto(gt(x, r), 2), ret),
      Vector(Cmd.Assign(r, int(10)), Cmd.Goto(gt(x, int(10)), 2), ret))

  test("it reaches through a fused compare's nop: if x OP literal, as javac wrote it"):
    // $r = TEN; $c = x.compareTo($r); if $c <= 0 goto 4  →  $r := 10; nop; if x <= 10
    val source = Seq(Cmd.Assign(r, LVal.StaticField("java.math.BigInteger", "TEN")),
      Cmd.Assign(c, virtual(CompareTo, x, r)), Cmd.Goto(RVal.Binop(c, BinOp.Le, int(0)), 4), Cmd.Nop, ret)
    assertEquals(lift(source*)(2), Cmd.Goto(RVal.Binop(x, BinOp.Le, int(10)), 4))

  test("not across a jump target: another path may arrive with a different value"):
    // 1 is the target of the goto at 2
    val source = Seq(Cmd.Assign(r, int(1)), Cmd.Goto(gt(x, r), 3), Cmd.Goto(RVal.BoolConst(true), 1), ret)
    assertEquals(lift(source*)(1), Cmd.Goto(gt(x, r), 3))

  test("not after a jump: the next command starts a block"):
    val source = Seq(Cmd.Assign(r, int(1)), Cmd.Goto(gt(x, int(0)), 2), Cmd.Goto(gt(x, r), 3), ret)
    assertEquals(lift(source*)(2), Cmd.Goto(gt(x, r), 3))

  test("not after the local is reassigned a non-constant"):
    val source = Seq(Cmd.Assign(r, int(1)), Cmd.Assign(r, static(RandInt)), Cmd.Goto(gt(x, r), 3), ret)
    assertEquals(lift(source*)(2), Cmd.Goto(gt(x, r), 3))

  test("the nearest assignment wins"):
    val source = Seq(Cmd.Assign(r, int(1)), Cmd.Assign(r, int(2)), Cmd.Goto(gt(x, r), 3), ret)
    assertEquals(lift(source*)(2), Cmd.Goto(gt(x, int(2)), 3))

  test("a user variable Soot reused for a constant is substituted too (§5.5)"):
    // i = valueOf(5); $c = x.compareTo(i); if $c <= 0: Soot's own output for x > 5 after a loop
    val i = LVal.Local("i", Big)
    val source = Seq(Cmd.Assign(i, static(ValueOf, int(5))), Cmd.Assign(c, virtual(CompareTo, x, i)),
      Cmd.Goto(RVal.Binop(c, BinOp.Le, int(0)), 4), Cmd.Nop, ret)
    assertEquals(lift(source*)(2), Cmd.Goto(RVal.Binop(x, BinOp.Le, int(5)), 4))

  test("assignment targets are never rewritten"):
    assertEquals(lift(Cmd.Assign(r, int(1)), Cmd.Assign(r, RVal.Binop(r, BinOp.Add, int(1))), ret),
      Vector(Cmd.Assign(r, int(1)), Cmd.Assign(r, RVal.Binop(int(1), BinOp.Add, int(1))), ret))
