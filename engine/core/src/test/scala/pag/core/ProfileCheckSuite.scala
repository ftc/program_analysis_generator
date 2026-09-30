package pag.core

import pag.ir.*

/** The profile check's list rules (implementation_strategy.md §5.2), on IR
  * programs built by hand in the shapes the Soot front end produces.
  */
class ProfileCheckSuite extends munit.FunSuite:

  val BigInteger: JType = JType.Ref("java.math.BigInteger")
  val Main: MethodId = MethodId("Probe", "main", List(JType.ArrayOf(JType.Ref("java.lang.String"))), JType.Void)
  def bigint(name: String, params: JType*): MethodId =
    MethodId("java.math.BigInteger", name, params.toList, BigInteger)
  val RandInt: MethodId = MethodId("pag.probe.Rand", "randInt", Nil, BigInteger)
  val Reach: MethodId = MethodId("pag.probe.Reach", "reach", List(JType.Prim(PrimKind.Int)), JType.Void)

  val x: LVal.Local = LVal.Local("x", BigInteger)
  val zero: LVal.Local = LVal.Local("$stack2", BigInteger)
  val cmp: LVal.Local = LVal.Local("$stack3", JType.Prim(PrimKind.Int))

  /** A program whose main is `cmds`, on source lines 10, 11, … */
  def program(cmds: Cmd*): Program =
    Program("Probe.java", List(Method(Main, cmds.toVector, Vector.tabulate(cmds.size)(10 + _))), Main)

  def violations(cmds: Cmd*): List[(String, String)] =
    ProfileCheck.check(program(cmds*), Profile.BigintMainV1).map(v => (v.construct, v.setting))

  def call(kind: InvokeKind, callee: MethodId, receiver: Option[RVal], args: RVal*): RVal.Invoke =
    RVal.Invoke(kind, callee, receiver, args.toList)

  /** `x = randInt(); if (x.compareTo(ZERO) < 0) reach(1); while (true) {}`, as Jimple has it. */
  val accepted: Seq[Cmd] = Seq(
    Cmd.Assign(x, call(InvokeKind.Static, RandInt, None)),
    Cmd.Assign(zero, LVal.StaticField("java.math.BigInteger", "ZERO")),
    Cmd.Assign(cmp, call(InvokeKind.Virtual, bigint("compareTo", BigInteger), Some(x), zero)),
    Cmd.Goto(RVal.Binop(cmp, BinOp.Ge, RVal.IntConst(0)), 5),
    Cmd.InvokeStmt(call(InvokeKind.Static, Reach, None, RVal.IntConst(1))),
    Cmd.Goto(RVal.BoolConst(true), 5),
    Cmd.Return(None)
  )

  test("a program inside the profile has no violations"):
    assertEquals(violations(accepted*), Nil)

  test("commands: a command not listed is a violation"):
    assertEquals(violations(Cmd.Throw), List(("command Throw", "commands")))

  test("lvals: assigning to anything but a local is a violation"):
    val write = Cmd.Assign(LVal.StaticField("java.math.BigInteger", "ONE"), x)
    assertEquals(violations(write), List(("assignment to StaticField", "lvals")))

  test("rvals: a value kind not listed is a violation"):
    assertEquals(violations(Cmd.Assign(x, RVal.StringConst("hi"))), List(("value StringConst", "rvals")))

  test("operators: arithmetic on ints is a violation; comparisons are not"):
    val i = LVal.Local("i", JType.Prim(PrimKind.Int))
    // i's int type is also a violation, under `types` (2a.5b); this test is about operators
    assertEquals(violations(Cmd.Assign(i, RVal.Binop(i, BinOp.Add, RVal.IntConst(1)))).filter(_._2 == "operators"),
      List(("operator Add", "operators")))

  test("invokes: a dispatch kind not listed is a violation"):
    val init = call(InvokeKind.Special, bigint("add", BigInteger), Some(x), x)
    assertEquals(violations(Cmd.Assign(x, init)), List(("Special invoke of java.math.BigInteger.add(java.math.BigInteger)", "invokes")))

  test("callees: a method not listed is a violation"):
    val divide = call(InvokeKind.Virtual, bigint("divide", BigInteger), Some(x), x)
    assertEquals(violations(Cmd.Assign(x, divide)), List(("call to java.math.BigInteger.divide", "callees")))

  test("staticFields: a static field not listed is a violation"):
    val out = LVal.StaticField("java.lang.System", "out")
    assertEquals(violations(Cmd.Assign(x, out)), List(("static field java.lang.System.out", "staticFields")))

  test("violations inside a value are found: a call's arguments"):
    val badArg = call(InvokeKind.Static, Reach, None, RVal.StringConst("one"))
    assertEquals(violations(Cmd.InvokeStmt(badArg)), List(("value StringConst", "rvals")))

  test("every violation is reported, not just the first"):
    val divide = call(InvokeKind.Virtual, bigint("divide", BigInteger), Some(x), x)
    assertEquals(violations(Cmd.Throw, Cmd.Assign(x, RVal.StringConst("hi")), Cmd.Assign(x, divide)).map(_._2),
      List("commands", "rvals", "callees"))

  test("a violation says where it is and which setting would allow it"):
    val v = ProfileCheck.check(program(Cmd.Nop, Cmd.Throw), Profile.BigintMainV1).head
    assertEquals((v.method, v.index, v.line), (Main, 1, Some(11)))
    assertEquals(v.message("Probe.java", "bigint-main-v1"),
      "Probe.java:11: command Throw is not allowed by profile bigint-main-v1 (language.commands)")

  // --- Structural rules (2a.5b)

  val StringArray: JType = JType.ArrayOf(JType.Ref("java.lang.String"))
  val args: LVal.Local = LVal.Local("args", StringArray)
  val bindArgs: Cmd = Cmd.Assign(args, LVal.Param(0, StringArray))
  val Init: MethodId = MethodId("Probe", "<init>", Nil, JType.Void)
  val self: LVal.Local = LVal.Local("this", JType.Ref("Probe"))
  val ObjectInit: MethodId = MethodId("java.lang.Object", "<init>", Nil, JType.Void)
  val generatedConstructor: Method = Method(Init, Vector(
    Cmd.Assign(self, LVal.This("Probe")),
    Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Special, ObjectInit, Some(self), Nil)),
    Cmd.Return(None)
  ), Vector(1, 1, 1))

  def withMethods(main: Seq[Cmd], others: Method*): List[(String, String)] =
    val m = Method(Main, main.toVector, Vector.tabulate(main.size)(10 + _))
    ProfileCheck.check(Program("Probe.java", m :: others.toList, Main), Profile.BigintMainV1)
      .map(v => (v.construct, v.setting))

  test("a whole program as the front end loads it: constructor, args binding, body"):
    assertEquals(withMethods(bindArgs +: accepted, generatedConstructor), Nil)

  test("methods: a second user method is a violation"):
    val helper = Method(MethodId("Probe", "helper", Nil, JType.Void), Vector(Cmd.Return(None)), Vector(20))
    assertEquals(withMethods(accepted, helper), List(("method Probe.helper()", "methods")))

  test("methods: a constructor that is not exactly the generated one is counted"):
    val written = generatedConstructor.copy(
      body = Cmd.Nop +: generatedConstructor.body, lines = 2 +: generatedConstructor.lines)
    assert(withMethods(accepted, written).contains(("method Probe.<init>()", "methods")))

  test("mainArgs: the binding alone is fine; any other use of args is a violation"):
    val n = LVal.Local("n", JType.Prim(PrimKind.Int))
    assertEquals(withMethods(Seq(bindArgs, Cmd.Return(None))), Nil)
    assert(withMethods(Seq(bindArgs, Cmd.Assign(n, RVal.ArrayLength(args)), Cmd.Return(None)))
      .contains(("read of main's parameter args", "mainArgs")))

  test("types: a local of an unlisted type is reported once, however often it is used"):
    val i = LVal.Local("i", JType.Prim(PrimKind.Int))
    val uses = Seq(Cmd.Assign(i, RVal.IntConst(5)), Cmd.Goto(RVal.Binop(i, BinOp.Lt, RVal.IntConst(3)), 2), Cmd.Return(None))
    assertEquals(withMethods(uses), List(("local i of type int", "types")))

  def compareTo(t: LVal.Local): Cmd =
    Cmd.Assign(t, call(InvokeKind.Virtual, bigint("compareTo", BigInteger), Some(x), zero))
  def branchOn(t: LVal.Local, k: RVal, op: BinOp = BinOp.Ge): Cmd = Cmd.Goto(RVal.Binop(t, op, k), 0)
  val misuse: String = "compare temp $stack3 used other than by the if right after it"

  test("compare temps: a temp used again after its if is a violation there"):
    val found = violations(compareTo(cmp), branchOn(cmp, RVal.IntConst(0)), Cmd.Nop, branchOn(cmp, RVal.IntConst(0)))
    assertEquals(found, List((misuse, "types")))

  test("compare temps: a temp whose if does not come right after it is a violation at both"):
    val reach = Cmd.InvokeStmt(call(InvokeKind.Static, Reach, None, RVal.IntConst(1)))
    assertEquals(violations(compareTo(cmp), reach, branchOn(cmp, RVal.IntConst(0))),
      List((misuse, "types"), (misuse, "types")))

  test("compare temps: a compareTo temp must be compared against 0"):
    assertEquals(violations(compareTo(cmp), branchOn(cmp, RVal.IntConst(1))).size, 2)

  test("compare temps: an equals temp is compared against false, not 0"):
    val eq = LVal.Local("$stack4", JType.Prim(PrimKind.Boolean))
    val equals = Cmd.Assign(eq, call(InvokeKind.Virtual,
      MethodId("java.math.BigInteger", "equals", List(JType.Ref("java.lang.Object")), JType.Prim(PrimKind.Boolean)),
      Some(x), zero))
    assertEquals(violations(equals, branchOn(eq, RVal.BoolConst(false), BinOp.Eq)), Nil)
    assertEquals(violations(equals, branchOn(eq, RVal.IntConst(0), BinOp.Eq)).size, 2)

  test("compare temps: Soot reusing one temp for two compares is fine, each with its own if"):
    assertEquals(violations(compareTo(cmp), branchOn(cmp, RVal.IntConst(0)),
      compareTo(cmp), branchOn(cmp, RVal.IntConst(0), BinOp.Lt)), Nil)
