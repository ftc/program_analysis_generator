package pag.core

import java.util.Optional
import scala.jdk.CollectionConverters.*
import pag.{api, ir}

/** The domain-vocabulary converter (implementation_strategy.md §5.4): the one
  * place the Scala IR becomes the Java records a domain receives. Trust base
  * (§2): a mistranslated `Sub` makes a correct domain wrong.
  *
  * One-to-one, except that types become strings, produced only by
  * `JType.toString`. A value the vocabulary cannot express — `Skip`, `Invoke`,
  * a disabled construct — means lowering emitted something it should not have,
  * so it is an engine bug and throws.
  */
object VocabularyConverter:

  def step(s: ir.Step): api.Step = s match
    case ir.Step.Assign(target, source) => api.Step.Assign(local(target), rval(source))
    case ir.Step.Assume(cond)           => api.Step.Assume(rval(cond))
    case ir.Step.Call(target, callee, args) =>
      api.Step.Call(Optional.ofNullable(target.map(local).orNull), methodId(callee), args.map(rval).asJava)
    case ir.Step.Skip => unexpressible(s, "the engine handles it as the identity")

  def rval(v: ir.RVal): api.RVal = v match
    case ir.RVal.IntConst(n)     => api.RVal.IntConst(n.bigInteger)
    case ir.RVal.Binop(l, op, r) => api.RVal.Binop(rval(l), binOp(op), rval(r))
    case l: ir.LVal.Local        => local(l)
    case ir.LVal.StaticField(declaringClass, name) => api.LVal.StaticField(declaringClass, name)
    case _: ir.RVal.Invoke       => unexpressible(v, "lowering turns calls into Step.Call")
    case _: ir.RVal.BoolConst    => unexpressible(v, "only Goto(true, …) has one, and it lowers to skip")
    case _: ir.LVal.Param        => unexpressible(v, "the args binding lowers to skip")
    case _: (ir.RVal.Cast | ir.RVal.NewObject | ir.RVal.StringConst | ir.RVal.InstanceOf | ir.RVal.ArrayLength |
          ir.LVal.This | ir.LVal.Field | ir.LVal.ArrayRef) =>
      unexpressible(v, "disabled in v1")

  def binOp(op: ir.BinOp): api.BinOp = op match
    case ir.BinOp.Mult => api.BinOp.Mult
    case ir.BinOp.Add  => api.BinOp.Add
    case ir.BinOp.Sub  => api.BinOp.Sub
    case ir.BinOp.Lt   => api.BinOp.Lt
    case ir.BinOp.Le   => api.BinOp.Le
    case ir.BinOp.Gt   => api.BinOp.Gt
    case ir.BinOp.Ge   => api.BinOp.Ge
    case ir.BinOp.Eq   => api.BinOp.Eq
    case ir.BinOp.Ne   => api.BinOp.Ne

  def methodId(m: ir.MethodId): api.MethodId =
    api.MethodId(m.declaringClass, m.name, m.paramTypes.map(_.toString).asJava, m.returnType.toString)

  /** Only a local can be assigned: the vocabulary's `Assign` and `Call` targets are `Local`. */
  private def local(l: ir.LVal): api.LVal.Local = l match
    case ir.LVal.Local(name, tpe) => api.LVal.Local(name, tpe.toString)
    case _                        => unexpressible(l, "only a local can be assigned")

  private def unexpressible(v: Any, why: String): Nothing =
    throw IllegalStateException(s"$v cannot reach a domain: $why")
