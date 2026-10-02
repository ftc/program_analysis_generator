package pag.core

import java.math.BigInteger
import java.util.Optional
import scala.jdk.CollectionConverters.*
import pag.{api, ir}
import pag.core.VocabularyConverter.*

/** The domain-vocabulary converter (implementation_strategy.md §5.4): one test
  * per case of its match, each with the Java record written out by hand.
  */
class VocabularyConverterSuite extends munit.FunSuite:

  val Big: ir.JType = ir.JType.Ref("java.math.BigInteger")
  val x: ir.LVal.Local = ir.LVal.Local("x", Big)
  val y: ir.LVal.Local = ir.LVal.Local("y", Big)
  val jx: api.LVal.Local = api.LVal.Local("x", "java.math.BigInteger")
  val jy: api.LVal.Local = api.LVal.Local("y", "java.math.BigInteger")
  def int(n: BigInt): ir.RVal.IntConst = ir.RVal.IntConst(n)
  def jint(n: String): api.RVal.IntConst = api.RVal.IntConst(BigInteger(n))
  val RandInt: ir.MethodId = ir.MethodId("pag.probe.Rand", "randInt", Nil, Big)
  val jRandInt: api.MethodId = api.MethodId("pag.probe.Rand", "randInt", List.empty[String].asJava, "java.math.BigInteger")

  def throwsUnexpressible(body: => Any): Unit =
    val e = intercept[IllegalStateException](body)
    assert(e.getMessage.contains("cannot reach a domain"), e.getMessage)

  // --- Step

  test("Assign: target and source"):
    assertEquals(step(ir.Step.Assign(x, y)), api.Step.Assign(jx, jy))

  test("Assume: the condition, unchanged"):
    assertEquals(
      step(ir.Step.Assume(ir.RVal.Binop(x, ir.BinOp.Lt, int(10)))),
      api.Step.Assume(api.RVal.Binop(jx, api.BinOp.Lt, jint("10")))
    )

  test("Call with a target"):
    assertEquals(step(ir.Step.Call(Some(x), RandInt, Nil)), api.Step.Call(Optional.of(jx), jRandInt, List.empty.asJava))

  test("Call without a target"):
    assertEquals(step(ir.Step.Call(None, RandInt, Nil)), api.Step.Call(Optional.empty, jRandInt, List.empty.asJava))

  test("Call: arguments in order"):
    val m = ir.MethodId("C", "f", List(Big, Big), ir.JType.Void)
    val jm = api.MethodId("C", "f", List("java.math.BigInteger", "java.math.BigInteger").asJava, "void")
    assertEquals(
      step(ir.Step.Call(None, m, List(y, int(3)))),
      api.Step.Call(Optional.empty, jm, List[api.RVal](jy, jint("3")).asJava)
    )

  test("Skip throws: the engine handles it"):
    throwsUnexpressible(step(ir.Step.Skip))

  test("Assign to anything but a local throws"):
    throwsUnexpressible(step(ir.Step.Assign(ir.LVal.StaticField("java.math.BigInteger", "ONE"), int(1))))

  // --- RVal

  test("IntConst: zero, negative, and beyond 64 bits"):
    assertEquals(rval(int(0)), jint("0"))
    assertEquals(rval(int(-7)), jint("-7"))
    assertEquals(rval(int(BigInt("123456789012345678901234567890"))), jint("123456789012345678901234567890"))
    assertEquals(rval(int(BigInt("-99999999999999999999"))), jint("-99999999999999999999"))

  test("Binop: operands keep their sides, nested on both"):
    // (x - 1) - (2 - y): swapping any pair of operands changes the value
    val v = ir.RVal.Binop(ir.RVal.Binop(x, ir.BinOp.Sub, int(1)), ir.BinOp.Sub, ir.RVal.Binop(int(2), ir.BinOp.Sub, y))
    val jv = api.RVal.Binop(
      api.RVal.Binop(jx, api.BinOp.Sub, jint("1")),
      api.BinOp.Sub,
      api.RVal.Binop(jint("2"), api.BinOp.Sub, jy)
    )
    assertEquals(rval(v), jv)

  test("Local: name, and type through JType.toString"):
    assertEquals(rval(ir.LVal.Local("i", ir.JType.Prim(ir.PrimKind.Int))), api.LVal.Local("i", "int"))
    assertEquals(rval(x), jx)

  test("StaticField"):
    assertEquals(
      rval(ir.LVal.StaticField("java.math.BigInteger", "TEN")),
      api.LVal.StaticField("java.math.BigInteger", "TEN")
    )

  test("Invoke throws: lowering turns calls into Step.Call"):
    throwsUnexpressible(rval(ir.RVal.Invoke(ir.InvokeKind.Static, RandInt, None, Nil)))

  test("BoolConst throws"):
    throwsUnexpressible(rval(ir.RVal.BoolConst(true)))

  test("Param throws"):
    throwsUnexpressible(rval(ir.LVal.Param(0, ir.JType.ArrayOf(ir.JType.Ref("java.lang.String")))))

  test("every construct disabled in v1 throws"):
    val disabled: List[ir.RVal] = List(
      ir.RVal.Cast(Big, x),
      ir.RVal.NewObject("C"),
      ir.RVal.StringConst("s"),
      ir.RVal.InstanceOf("C", x),
      ir.RVal.ArrayLength(x),
      ir.LVal.This("C"),
      ir.LVal.Field(x, "C", "f"),
      ir.LVal.ArrayRef(x, int(0))
    )
    for v <- disabled do throwsUnexpressible(rval(v))

  test("an unexpressible value nested in an expressible one still throws"):
    throwsUnexpressible(rval(ir.RVal.Binop(x, ir.BinOp.Add, ir.RVal.StringConst("s"))))
    throwsUnexpressible(step(ir.Step.Assume(ir.RVal.Binop(ir.RVal.BoolConst(true), ir.BinOp.Eq, x))))

  // --- BinOp and MethodId

  test("BinOp: each operator to its namesake, written out by hand"):
    val expected = List(
      ir.BinOp.Mult -> api.BinOp.Mult,
      ir.BinOp.Add  -> api.BinOp.Add,
      ir.BinOp.Sub  -> api.BinOp.Sub,
      ir.BinOp.Lt   -> api.BinOp.Lt,
      ir.BinOp.Le   -> api.BinOp.Le,
      ir.BinOp.Gt   -> api.BinOp.Gt,
      ir.BinOp.Ge   -> api.BinOp.Ge,
      ir.BinOp.Eq   -> api.BinOp.Eq,
      ir.BinOp.Ne   -> api.BinOp.Ne
    )
    for (op, jop) <- expected do assertEquals(binOp(op), jop, op)
    // the table above covers both enums completely
    assertEquals(expected.map(_._1).toSet, ir.BinOp.values.toSet)
    assertEquals(expected.map(_._2).toSet, api.BinOp.values.toSet)

  test("MethodId: every kind of type through JType.toString"):
    val m = ir.MethodId(
      "Probe",
      "main",
      List(ir.JType.ArrayOf(ir.JType.Ref("java.lang.String")), ir.JType.Prim(ir.PrimKind.Long),
        ir.JType.ArrayOf(ir.JType.ArrayOf(ir.JType.Prim(ir.PrimKind.Int)))),
      ir.JType.Void
    )
    assertEquals(methodId(m), api.MethodId("Probe", "main", List("java.lang.String[]", "long", "int[][]").asJava, "void"))
    assertEquals(methodId(RandInt), jRandInt)
