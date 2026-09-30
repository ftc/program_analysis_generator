package pag.frontend

import pag.ir.*

/** SootIrProvider on straight-line code (implementation_strategy.md §5.5). The
  * expected IR is worked out by hand from the fixture source, not copied from
  * the provider's output.
  */
class SootIrProviderSuite extends munit.FunSuite:

  val provider = SootIrProvider()
  def load(fixture: String): Program = Fixtures.withCompiled(fixture)(provider.load)

  val BigInteger: JType = JType.Ref("java.math.BigInteger")
  val StringArray: JType = JType.ArrayOf(JType.Ref("java.lang.String"))
  val Main: MethodId = MethodId("StraightLine", "main", List(StringArray), JType.Void)
  val Init: MethodId = MethodId("StraightLine", "<init>", Nil, JType.Void)
  val RandInt: MethodId = MethodId("pag.probe.Rand", "randInt", Nil, BigInteger)
  val Reach: MethodId = MethodId("pag.probe.Reach", "reach", List(JType.Prim(PrimKind.Int)), JType.Void)
  val Add: MethodId = MethodId("java.math.BigInteger", "add", List(BigInteger), BigInteger)
  val ObjectInit: MethodId = MethodId("java.lang.Object", "<init>", Nil, JType.Void)

  def local(name: String, t: JType = BigInteger): LVal.Local = LVal.Local(name, t)
  def method(p: Program, id: MethodId): Method = p.methods.find(_.id == id).get

  test("the program is the one class, entered at main"):
    val p = load("StraightLine")
    assertEquals(p.sourceFile, "StraightLine.java")
    assertEquals(p.entryMethod, Main)
    assertEquals(p.methods.map(_.id).toSet, Set(Main, Init))

  test("main translates command by command, with its source lines"):
    val m = method(load("StraightLine"), Main)
    val one = m.body(2) match // Soot names this temporary; its name is not ours to choose
      case Cmd.Assign(t: LVal.Local, _) => t
      case other                        => fail(s"expected the temporary for ONE, got $other")
    assertEquals(m.body, Vector(
      Cmd.Assign(local("args", StringArray), LVal.Param(0, StringArray)),
      Cmd.Assign(local("x"), RVal.Invoke(InvokeKind.Static, RandInt, None, Nil)),
      Cmd.Assign(one, LVal.StaticField("java.math.BigInteger", "ONE")),
      // y is never read, so Soot keeps the call and drops the store to y (§5.5)
      Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Virtual, Add, Some(local("x")), List(one))),
      Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Static, Reach, None, List(RVal.IntConst(1)))),
      Cmd.Return(None)
    ))
    assertEquals(m.lines, Vector(Method.UnknownLine, 7, 8, 8, 9, 10))

  /** Not a test that jb.dae is off: in the jb pack it only ever touches Soot's
    * $stack temporaries (only-stack-locals defaults to true), which no Java
    * source can make dead with a pure right-hand side. configure's loud failure
    * guards that setting instead. This pins what Soot does to programmers' dead
    * stores, which the IR and its line mapping depend on (§5.5).
    */
  test("never-read stores keep their commands"):
    val p = load("DeadStores")
    val m = p.methods.find(_.id.name == "main").get
    // z = TEN: a dead store to a variable, kept as an assignment
    assert(m.body.contains(Cmd.Assign(local("z"), LVal.StaticField("java.math.BigInteger", "TEN"))), m.body)
    // y = x.add(ONE): Soot drops the store but keeps the call, and line 8 keeps a command
    assert(m.body.exists { case Cmd.InvokeStmt(RVal.Invoke(_, Add, _, _)) => true; case _ => false }, m.body)
    assertEquals(m.locationsOn(8, true).isEmpty, false)

  test("the generated constructor translates too"):
    val m = method(load("StraightLine"), Init)
    val self = local("this", JType.Ref("StraightLine"))
    assertEquals(m.body, Vector(
      Cmd.Assign(self, LVal.This("StraightLine")),
      Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Special, ObjectInit, Some(self), Nil)),
      Cmd.Return(None)
    ))

  test("bytecode not yet translated is Untranslatable, naming method and line"):
    val e = intercept[Untranslatable](load("Unsupported"))
    assert(e.getMessage.contains("Unsupported.main(java.lang.String[])"), e.getMessage)
    assert(e.getMessage.contains("line 5"), e.getMessage)

  test("a directory with more than one class is rejected"):
    intercept[IllegalArgumentException](load("TwoClasses"))

  /** TODO(shawn) this behavior will need to change later to analyze code written for frameworks that provide
       a main method. E.g. Temporal and Android where client code never writes "public static main...".*/
  test("a class without main is rejected"):
    intercept[IllegalArgumentException](load("NoMain"))

  /** Soot's Scene is global; G.reset must make each load independent of the last. */
  test("loading the same class again, after another, gives the same program"):
    val first = load("StraightLine")
    intercept[Untranslatable](load("Unsupported"))
    assertEquals(load("StraightLine"), first)

  // --- Branches (2a.2). Indices are worked out by hand from how javac compiles
  // each fixture: every `if` jumps past its body when the source condition is
  // false, so javac emits the negated comparison.

  def mainOf(fixture: String): Method = load(fixture).methods.find(_.id.name == "main").get

  def gotos(m: Method): List[(Int, Cmd.Goto)] =
    m.body.zipWithIndex.collect { case (g: Cmd.Goto, i) => (i, g) }.toList

  /** The operator and target of a branch on a compare temp against a constant. */
  def branch(g: Cmd.Goto): (BinOp, RVal, Int) = g.cond match
    case RVal.Binop(LVal.Local(_, _), op, rhs) => (op, rhs, g.target)
    case other                                 => fail(s"expected temp OP constant, got $other")

  test("each comparison translates with javac's negated operator and the right target"):
    // Body: 0 args, 1 x = randInt(); then per `if` k = 0..5 four units:
    // ZERO at 2+4k, compareTo at 3+4k, the if at 4+4k, reach at 5+4k. Each if
    // skips its reach, to 6+4k; the last lands on return at 26.
    val found = gotos(mainOf("Comparisons")).map((i, g) => (i, branch(g)))
    val zero = RVal.IntConst(0)
    assertEquals(found, List(
      (4,  (BinOp.Ge, zero, 6)),   // source <
      (8,  (BinOp.Gt, zero, 10)),  // source <=
      (12, (BinOp.Le, zero, 14)),  // source >
      (16, (BinOp.Lt, zero, 18)),  // source >=
      (20, (BinOp.Ne, zero, 22)),  // source ==
      (24, (BinOp.Eq, zero, 26))   // source !=
    ))

  test("a while loop: the exit branch jumps forward, the back edge jumps to the test"):
    // 0 args, 1 i = ZERO, 2 TEN, 3 compareTo, 4 if >= 0 goto 8, 5 ONE, 6 i = add,
    // 7 goto 2, 8 reach(1), 9 return
    val m = mainOf("Loop")
    assertEquals(gotos(m).map(_._1), List(4, 7))
    assertEquals(branch(m.body(4).asInstanceOf[Cmd.Goto]), (BinOp.Ge, RVal.IntConst(0), 8))
    assertEquals(m.body(7), Cmd.Goto(RVal.BoolConst(true), 2))

  test("an equals test compares its boolean temp against false"):
    // 0 args, 1 x = randInt(), 2 TEN, 3 equals, 4 if $z == false goto 6, 5 reach(1), 6 return
    val m = mainOf("Equals")
    m.body(4) match
      case Cmd.Goto(RVal.Binop(LVal.Local(_, t), BinOp.Eq, RVal.BoolConst(false)), 6) =>
        assertEquals(t, JType.Prim(PrimKind.Boolean))
      case other => fail(s"expected `if $$z == false goto 6`, got $other")
