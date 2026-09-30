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
    program.methods.filterNot(isGeneratedConstructor).flatMap { m =>
      def at(i: Int)(found: List[(String, String)]) =
        found.map((c, s) => ProfileViolation(m.id, i, m.lineOf(i), c, s))
      val args = if m.id == program.entryMethod then argsBinding(m) else None
      val temps = compareTemps(m)
      val occurs = m.body.map(localsIn)
      val methodRule =
        if profile.methods(m.id.name) then Nil else at(0)(List((s"method ${m.id}", "methods")))
      val perCommand = m.body.indices.toList.flatMap { i =>
        val lists = if args.exists(_._1 == i) then Nil else Rules(profile).command(m.body(i))
        val argReads = args.toList.collect {
          case (bound, l) if bound != i && occurs(i).contains(l) => (s"read of main's parameter ${l.name}", "mainArgs")
        }
        val tempMisuse = temps.misusedAt(i).map(t => (s"compare temp ${t.name} used other than by the if right after it", "types"))
        at(i)(lists ++ argReads ++ tempMisuse)
      }
      val excepted = args.map(_._2).toSet ++ temps.locals
      val typeRule = occurs.zipWithIndex.flatMap((ls, i) => ls.map((_, i)))
        .distinctBy(_._1).toList
        .collect { case (l, i) if !excepted(l) && !profile.types(l.tpe.toString) =>
          at(i)(List((s"local ${l.name} of type ${l.tpe}", "types"))) }.flatten
      methodRule ++ perCommand ++ typeRule
    }

  /** javac's default constructor, exactly: bind this, call Object.<init>, return. Not counted (§5.2). */
  private def isGeneratedConstructor(m: Method): Boolean =
    val objectInit = MethodId("java.lang.Object", "<init>", Nil, JType.Void)
    m.id.name == "<init>" && m.id.paramTypes.isEmpty && (m.body match
      case Vector(Cmd.Assign(self: LVal.Local, LVal.This(_)),
                  Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Special, `objectInit`, Some(recv), Nil)),
                  Cmd.Return(None)) => recv == self
      case _ => false)

  /** The `args := @parameter0` binding: its index and the local it binds. */
  private def argsBinding(m: Method): Option[(Int, LVal.Local)] =
    m.body.zipWithIndex.collectFirst { case (Cmd.Assign(l: LVal.Local, LVal.Param(0, _)), i) => (i, l) }

  /** Compare temps (§5.2): locals assigned a BigInteger compareTo or equals. Each
    * definition must be followed at once by `if temp OP k`, k being 0 for
    * compareTo or false for equals, and that is the temp's only use.
    */
  private final case class CompareTemps(locals: Set[LVal.Local], misusedAt: Int => List[LVal.Local])

  private def compareTemps(m: Method): CompareTemps =
    def constantFor(c: Cmd): Option[(LVal.Local, RVal)] = c match
      case Cmd.Assign(t: LVal.Local, RVal.Invoke(_, callee, _, _)) => callee.qualifiedName match
        case "java.math.BigInteger.compareTo" => Some((t, RVal.IntConst(0)))
        case "java.math.BigInteger.equals"    => Some((t, RVal.BoolConst(false)))
        case _                                => None
      case _ => None
    val defs = m.body.zipWithIndex.flatMap((c, i) => constantFor(c).map((i, _))).toMap
    val temps = defs.values.map(_._1).toSet
    // A definition at i is well-formed when i+1 is `if temp OP k goto …`; both are allowed uses.
    val allowed = defs.toList.flatMap { case (i, (t, k)) =>
      m.body.lift(i + 1) match
        case Some(Cmd.Goto(RVal.Binop(`t`, _, `k`), _)) => List(i, i + 1)
        case _                                        => Nil
    }.toSet
    CompareTemps(temps, i => if allowed(i) then Nil else localsIn(m.body(i)).filter(temps).distinct)

  /** Every local a command mentions, as target or value. */
  private def localsIn(c: Cmd): List[LVal.Local] = c match
    case Cmd.Assign(t, s)     => localsIn(t) ++ localsIn(s)
    case Cmd.Goto(cond, _)    => localsIn(cond)
    case Cmd.Return(v)        => v.toList.flatMap(localsIn)
    case Cmd.InvokeStmt(call) => localsIn(call)
    case Cmd.Nop | Cmd.Throw  => Nil

  private def localsIn(v: RVal): List[LVal.Local] = v match
    case l: LVal.Local                   => List(l)
    case RVal.Binop(l, _, r)             => localsIn(l) ++ localsIn(r)
    case RVal.Invoke(_, _, recv, args)   => recv.toList.flatMap(localsIn) ++ args.flatMap(localsIn)
    case RVal.Cast(_, x)                 => localsIn(x)
    case RVal.InstanceOf(_, l)           => List(l)
    case RVal.ArrayLength(l)             => List(l)
    case LVal.Field(base, _, _)          => List(base)
    case LVal.ArrayRef(b, i)             => localsIn(b) ++ localsIn(i)
    case RVal.IntConst(_) | RVal.BoolConst(_) | RVal.NewObject(_) | RVal.StringConst(_) |
        LVal.Param(_, _) | LVal.This(_) | LVal.StaticField(_, _) => Nil

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
