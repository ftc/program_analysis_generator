package pag.core

import pag.ir.*

/** One construct outside the profile, where it is, and the setting that would
  * allow it (implementation_strategy.md §5.2).
  */
final case class ProfileViolation(
    method: MethodId,
    index: Int,
    line: Option[Int],
    construct: String,
    setting: String
):
  def message(sourceFile: String, profile: String): String =
    val at = line.fold(s"$sourceFile, $method, command $index")(n => s"$sourceFile:$n")
    s"$at: $construct is not allowed by profile $profile (language.$setting)"

/** The profile check: every construct in a program that its profile does not
  * accept. Reports all of them, never just the first. Trust base (§2).
  */
object ProfileCheck:

  def check(program: Program, profile: Profile): List[ProfileViolation] =
    for
      m <- program.methods
      (c, i) <- m.body.zipWithIndex.toList
      (construct, setting) <- Rules(profile).command(c)
    yield ProfileViolation(m.id, i, m.lineOf(i), construct, setting)

  /** Each rule yields (construct, setting) for every violation it finds. */
  private final class Rules(p: Profile):

    private def allowed(ok: Boolean, construct: => String, setting: String) =
      if ok then Nil else List((construct, setting))

    def command(c: Cmd): List[(String, String)] =
      val kind = c match
        case _: Cmd.Assign     => "Assign"
        case _: Cmd.Goto       => "Goto"
        case Cmd.Nop           => "Nop"
        case _: Cmd.Return     => "Return"
        case _: Cmd.InvokeStmt => "InvokeStmt"
        case Cmd.Throw         => "Throw"
      val parts = c match
        case Cmd.Assign(t, s)     => target(t) ++ value(s)
        case Cmd.Goto(cond, _)    => value(cond)
        case Cmd.Return(v)        => v.toList.flatMap(value)
        case Cmd.InvokeStmt(call) => value(call)
        case Cmd.Nop | Cmd.Throw  => Nil
      allowed(p.commands(kind), s"command $kind", "commands") ++ parts

    private def target(t: LVal): List[(String, String)] =
      allowed(p.lvals(name(t)), s"assignment to ${name(t)}", "lvals") ++ inside(t)

    private def value(v: RVal): List[(String, String)] =
      allowed(p.rvals(name(v)), s"value ${name(v)}", "rvals") ++ inside(v)

    /** The rules on a value's parts: operators, calls, static fields, sub-values. */
    private def inside(v: RVal): List[(String, String)] = v match
      case RVal.Binop(l, op, r) =>
        allowed(p.operators(op), s"operator $op", "operators") ++ value(l) ++ value(r)
      case RVal.Invoke(kind, callee, receiver, args) =>
        allowed(p.invokes(kind), s"$kind invoke of $callee", "invokes") ++
          allowed(p.callees(callee.qualifiedName), s"call to ${callee.qualifiedName}", "callees") ++
          receiver.toList.flatMap(value) ++ args.flatMap(value)
      case LVal.StaticField(cls, field) =>
        allowed(p.staticFields(s"$cls.$field"), s"static field $cls.$field", "staticFields")
      case RVal.Cast(_, x)       => value(x)
      case LVal.Field(base, _, _) => value(base)
      case LVal.ArrayRef(b, i)   => value(b) ++ value(i)
      case RVal.InstanceOf(_, l) => value(l)
      case RVal.ArrayLength(l)   => value(l)
      case RVal.IntConst(_) | RVal.BoolConst(_) | RVal.NewObject(_) | RVal.StringConst(_) |
          LVal.Local(_, _) | LVal.Param(_, _) | LVal.This(_) => Nil

  /** The IR case name a profile list uses for a value. */
  private def name(v: RVal): String = v match
    case _: RVal.IntConst    => "IntConst"
    case _: RVal.BoolConst   => "BoolConst"
    case _: RVal.Binop       => "Binop"
    case _: RVal.Invoke      => "Invoke"
    case _: RVal.Cast        => "Cast"
    case _: RVal.NewObject   => "NewObject"
    case _: RVal.StringConst => "StringConst"
    case _: RVal.InstanceOf  => "InstanceOf"
    case _: RVal.ArrayLength => "ArrayLength"
    case _: LVal.Local       => "Local"
    case _: LVal.Param       => "Param"
    case _: LVal.StaticField => "StaticField"
    case _: LVal.This        => "This"
    case _: LVal.Field       => "Field"
    case _: LVal.ArrayRef    => "ArrayRef"
