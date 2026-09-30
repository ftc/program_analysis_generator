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
    assertEquals(violations(Cmd.Assign(i, RVal.Binop(i, BinOp.Add, RVal.IntConst(1)))),
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
