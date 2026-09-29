package pag.ir

/** Binary operators. All six comparisons reach domains unnormalised (§5.1). */
enum BinOp:
  case Mult, Add, Sub, Lt, Le, Gt, Ge, Eq, Ne

/** Dispatch kind of an invoke. A source-IR fact only: lowering erases it (§5.3). */
enum InvokeKind:
  case Static, Virtual, Special, Interface

/** A value of the source IR (implementation_strategy.md §5.1). Everything here
  * is representable; the language profile (§5.2) decides what is accepted.
  */
sealed trait RVal

object RVal:
  /** int, long and lifted BigInteger constants alike. */
  final case class IntConst(v: BigInt) extends RVal
  final case class BoolConst(v: Boolean) extends RVal
  final case class Binop(l: RVal, op: BinOp, r: RVal) extends RVal

  /** A Jimple invoke expression. A Static call has no receiver; every other kind has one. */
  final case class Invoke(kind: InvokeKind, callee: MethodId, receiver: Option[RVal], args: List[RVal])
      extends RVal:
    require(
      (kind == InvokeKind.Static) != receiver.isDefined,
      s"$kind invoke of $callee ${if receiver.isDefined then "has a receiver" else "has no receiver"}"
    )

  // Disabled in v1.
  final case class Cast(tpe: JType, v: RVal) extends RVal:
    JType.requireValueType(tpe, "a cast")
  final case class NewObject(className: String) extends RVal
  final case class StringConst(v: String) extends RVal
  final case class InstanceOf(clazz: String, target: LVal.Local) extends RVal
  final case class ArrayLength(l: LVal.Local) extends RVal

/** An assignable value of the source IR (§5.1). */
sealed trait LVal extends RVal

object LVal:
  final case class Local(name: String, tpe: JType) extends LVal:
    JType.requireValueType(tpe, s"local $name")

  /** v1: only main's args binding. */
  final case class Param(index: Int, tpe: JType) extends LVal:
    require(index >= 0, s"negative parameter index $index")
    JType.requireValueType(tpe, s"parameter $index")

  /** v1: only the BigInteger constants ZERO, ONE, TWO and TEN. */
  final case class StaticField(declaringClass: String, name: String) extends LVal

  // Disabled in v1.
  final case class This(className: String) extends LVal
  final case class Field(base: Local, declType: String, name: String) extends LVal
  final case class ArrayRef(base: RVal, index: RVal) extends LVal
