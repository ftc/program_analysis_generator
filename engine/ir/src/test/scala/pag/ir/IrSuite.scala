package pag.ir

/** The IR types reject contradictory data (implementation_strategy.md §5.1, §5.3). */
class IrSuite extends munit.FunSuite:

  val BigInteger: JType = JType.Ref("java.math.BigInteger")
  val StringArray: JType = JType.ArrayOf(JType.Ref("java.lang.String"))
  val X: LVal.Local = LVal.Local("x", BigInteger)
  val One: RVal = RVal.IntConst(1)
  val Ret: Cmd = Cmd.Return(None)
  val Main: MethodId = MethodId("Probe", "main", List(StringArray), JType.Void)
  val Add: MethodId = MethodId("java.math.BigInteger", "add", List(BigInteger), BigInteger)
  val RandInt: MethodId = MethodId("pag.probe.Rand", "randInt", Nil, BigInteger)

  def method(body: Cmd*)(lines: Int*): Method = Method(Main, body.toVector, lines.toVector)
  def rejects(body: => Any): Unit = intercept[IllegalArgumentException](body)

  // --- JType

  test("each type has one printed form, as Java source writes it"):
    assertEquals(JType.Prim(PrimKind.Int).toString, "int")
    assertEquals(JType.Prim(PrimKind.Boolean).toString, "boolean")
    assertEquals(JType.Void.toString, "void")
    assertEquals(BigInteger.toString, "java.math.BigInteger")
    assertEquals(StringArray.toString, "java.lang.String[]")
    assertEquals(JType.ArrayOf(JType.ArrayOf(JType.Prim(PrimKind.Long))).toString, "long[][]")

  test("void is rejected wherever a value needs a type"):
    rejects(MethodId("Probe", "f", List(JType.Void), JType.Void))
    rejects(LVal.Local("x", JType.Void))
    rejects(LVal.Param(0, JType.Void))
    rejects(RVal.Cast(JType.Void, One))

  test("void is a valid return type"):
    assertEquals(Main.returnType, JType.Void)

  // --- MethodId

  test("a MethodId prints as qualified name and parameters"):
    assertEquals(Main.toString, "Probe.main(java.lang.String[])")
    assertEquals(Add.qualifiedName, "java.math.BigInteger.add")

  test("overloads are different methods with the same qualified name"):
    val addLong = Add.copy(paramTypes = List(JType.Prim(PrimKind.Long)))
    assertNotEquals(Add, addLong)
    assertEquals(Add.qualifiedName, addLong.qualifiedName)

  // --- Loc, Cmd, RVal

  test("locations are values, so they work as map keys"):
    assertEquals(Loc.AppLoc(Main, 3, true), Loc.AppLoc(Main, 3, true))
    assertEquals(Loc.AppLoc(Main, 3, true).hashCode, Loc.AppLoc(Main, 3, true).hashCode)
    assertNotEquals(Loc.AppLoc(Main, 3, true), Loc.AppLoc(Main, 3, false))

  test("a negative command index is rejected"):
    rejects(Loc.AppLoc(Main, -1, true))

  test("a negative jump target is rejected"):
    rejects(Cmd.Goto(RVal.BoolConst(true), -1))

  test("a negative parameter index is rejected"):
    rejects(LVal.Param(-1, StringArray))

  test("a static invoke with a receiver is rejected"):
    rejects(RVal.Invoke(InvokeKind.Static, RandInt, Some(X), Nil))

  test("a virtual invoke without a receiver is rejected"):
    rejects(RVal.Invoke(InvokeKind.Virtual, Add, None, List(One)))

  // --- Method

  test("body and lines must be the same length"):
    rejects(method(Cmd.Nop, Ret)(3))

  test("a line must be positive or unknown"):
    rejects(method(Ret)(0))
    method(Ret)(Method.UnknownLine) // accepted

  test("a jump past the last command is rejected"):
    val jump = Cmd.Goto(RVal.BoolConst(true), 2)
    rejects(method(jump, Ret)(3, 4))
    method(jump, Cmd.Nop, Ret)(3, 4, 5) // target 2 exists: accepted

  test("lineOf knows which lines are unknown"):
    val m = method(Cmd.Nop, Ret)(Method.UnknownLine, 7)
    assertEquals(m.lineOf(0), None)
    assertEquals(m.lineOf(1), Some(7))

  test("a line maps to every command on it, in body order"):
    // lines 5 7 5 8: line 5 holds commands 0 and 2, as a for header does
    val m = method(Cmd.Nop, Cmd.Nop, Cmd.Nop, Ret)(5, 7, 5, 8)
    assertEquals(m.locationsOn(5, true), List(Loc.AppLoc(Main, 0, true), Loc.AppLoc(Main, 2, true)))
    assertEquals(m.locationsOn(5, false), List(Loc.AppLoc(Main, 0, false), Loc.AppLoc(Main, 2, false)))

  test("a line with no commands maps to nothing"):
    assertEquals(method(Ret)(3).locationsOn(4, true), Nil)

  // --- Program

  test("the entry method must exist"):
    val main = method(Ret)(3)
    rejects(Program("P.java", List(main), Main.copy(name = "run")))
    Program("P.java", List(main), Main) // accepted

  test("two methods cannot share an id"):
    val main = method(Ret)(3)
    rejects(Program("P.java", List(main, main), Main))

  test("overloads can share a program"):
    val other = Method(Main.copy(paramTypes = Nil), Vector(Ret), Vector(9))
    Program("P.java", List(method(Ret)(3), other), Main) // accepted

  // --- Matches over the sealed types have no wildcard. -Wconf:id=E029:e makes a
  // missing case a compile error, so adding a case without handling it here fails
  // the build.

  def kind(c: Cmd): String = c match
    case Cmd.Assign(_, _)  => "assign"
    case Cmd.Goto(_, _)    => "goto"
    case Cmd.Nop           => "nop"
    case Cmd.Return(_)     => "return"
    case Cmd.InvokeStmt(_) => "invoke"
    case Cmd.Throw         => "throw"

  def kind(s: Step): String = s match
    case Step.Assign(_, _)  => "assign"
    case Step.Assume(_)     => "assume"
    case Step.Call(_, _, _) => "call"
    case Step.Skip          => "skip"

  def kind(v: RVal): String = v match
    case RVal.IntConst(_)          => "int"
    case RVal.BoolConst(_)         => "bool"
    case RVal.Binop(_, _, _)       => "binop"
    case RVal.Invoke(_, _, _, _)   => "invoke"
    case RVal.Cast(_, _)           => "cast"
    case RVal.NewObject(_)         => "new"
    case RVal.StringConst(_)       => "string"
    case RVal.InstanceOf(_, _)     => "instanceof"
    case RVal.ArrayLength(_)       => "length"
    case LVal.Local(_, _)          => "local"
    case LVal.Param(_, _)          => "param"
    case LVal.StaticField(_, _)    => "static"
    case LVal.This(_)              => "this"
    case LVal.Field(_, _, _)       => "field"
    case LVal.ArrayRef(_, _)       => "array"

  test("matches over the sealed types are exhaustive, and destructure"):
    assertEquals(kind(Cmd.Nop), "nop")
    assertEquals(kind(Step.Skip), "skip")
    assertEquals(kind(X: RVal), "local")
