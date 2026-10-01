package pag.core

import pag.ir.*

/** Lifting: BigInteger calls back to arithmetic and comparisons, so a domain
  * sees `y := x + 1` rather than `y = x.add(ONE)` (implementation_strategy.md
  * §5.7). Source IR to source IR; never adds or removes a command, so every
  * location and line survives. Trust base (§2).
  */
object Lifting:

  /** Strict: the profile check passed, so a misshapen compare is a bug and fails
    * loudly. Lenient (enforce = false, §5.2): leave anything unrecognised as is.
    */
  enum Mode:
    case Strict, Lenient

  def lift(program: Program, mode: Mode): Program =
    program.copy(methods = program.methods.map(m => m.copy(body = liftBody(m, mode))))

  private val BigInteger = "java.math.BigInteger"

  private def liftBody(m: Method, mode: Mode): Vector[Cmd] =
    val fused = fuseCompares(m, mode)
    val lifted = substituteConstants(fused.map(liftCmd))
    assert(lifted.size == m.body.size, s"${m.id}: lifting changed the number of commands")
    lifted

  /** `t := a.compareTo(b); if t OP 0 goto x`  becomes `nop; if a OP b goto x`.
    * `t := a.equals(b); if t ==/!= false goto x` becomes `nop; if a ≠/= b goto x`.
    */
  private def fuseCompares(m: Method, mode: Mode): Vector[Cmd] =
    val body = m.body.toArray
    for i <- body.indices do body(i) match
      case Cmd.Assign(t: LVal.Local, RVal.Invoke(InvokeKind.Virtual, callee, Some(a), List(b)))
          if callee.declaringClass == BigInteger && (callee.name == "compareTo" || callee.name == "equals") =>
        val fusedIf = body.lift(i + 1).collect {
          case Cmd.Goto(RVal.Binop(`t`, op, RVal.IntConst(k)), target) if callee.name == "compareTo" && k == 0 =>
            Cmd.Goto(RVal.Binop(a, op, b), target)
          case Cmd.Goto(RVal.Binop(`t`, BinOp.Eq, RVal.BoolConst(false)), target) if callee.name == "equals" =>
            Cmd.Goto(RVal.Binop(a, BinOp.Ne, b), target)
          case Cmd.Goto(RVal.Binop(`t`, BinOp.Ne, RVal.BoolConst(false)), target) if callee.name == "equals" =>
            Cmd.Goto(RVal.Binop(a, BinOp.Eq, b), target)
        }
        (fusedIf, mode) match
          case (Some(g), _) =>
            body(i) = Cmd.Nop
            body(i + 1) = g
          case (None, Mode.Strict) =>
            throw IllegalStateException(s"${m.id}, command $i: ${callee.name} temp ${t.name} is not " +
              "tested by the next if; the profile check should have rejected this (a bug)")
          case (None, Mode.Lenient) => ()
      case _ => ()
    body.toVector

  /** Constant substitution (§5.7): within a basic block, a use of local r becomes
    * c when the nearest earlier assignment to r in the block is `r := c`. The
    * assignment stays, so any other use still reads it; only value positions are
    * rewritten, never assignment targets. A block starts at 0, at every jump
    * target, and after every jump, return or throw.
    */
  private def substituteConstants(body: Vector[Cmd]): Vector[Cmd] =
    val targets = body.collect { case Cmd.Goto(_, t) => t }.toSet
    def endsBlock(c: Cmd) = c match
      case _: Cmd.Goto | _: Cmd.Return | Cmd.Throw => true
      case _                                     => false
    var known = Map.empty[LVal.Local, RVal.IntConst]
    body.indices.toVector.map { i =>
      if i == 0 || targets(i) || endsBlock(body(i - 1)) then known = Map.empty
      val c = substituteIn(body(i), known)
      c match
        case Cmd.Assign(r: LVal.Local, k: RVal.IntConst) => known += r -> k
        case Cmd.Assign(r: LVal.Local, _)                => known -= r
        case _                                           => ()
      c
    }

  private def substituteIn(c: Cmd, known: Map[LVal.Local, RVal.IntConst]): Cmd =
    def value(v: RVal): RVal = v match
      case l: LVal.Local                 => known.getOrElse(l, l)
      case RVal.Binop(l, op, r)          => RVal.Binop(value(l), op, value(r))
      case i @ RVal.Invoke(_, _, recv, args) => i.copy(receiver = recv.map(value), args = args.map(value))
      case _                             => v
    c match
      case Cmd.Assign(t, s)     => Cmd.Assign(t, value(s))
      case Cmd.Goto(cond, to)   => Cmd.Goto(value(cond), to)
      case Cmd.Return(v)        => Cmd.Return(v.map(value))
      case Cmd.InvokeStmt(call) => Cmd.InvokeStmt(call.copy(receiver = call.receiver.map(value), args = call.args.map(value)))
      case Cmd.Nop | Cmd.Throw  => c

  private def liftCmd(c: Cmd): Cmd = c match
    case Cmd.Assign(t, s)     => Cmd.Assign(t, liftValue(s))
    case Cmd.Goto(cond, to)   => Cmd.Goto(liftValue(cond), to)
    case Cmd.Return(v)        => Cmd.Return(v.map(liftValue))
    case Cmd.InvokeStmt(call) => Cmd.InvokeStmt(liftInvoke(call))
    case Cmd.Nop | Cmd.Throw  => c

  private def liftInvoke(call: RVal.Invoke): RVal.Invoke =
    call.copy(receiver = call.receiver.map(liftValue), args = call.args.map(liftValue))

  /** The per-value rows of §5.7's table; anything else is lifted inside and kept. */
  private def liftValue(v: RVal): RVal = v match
    case RVal.Invoke(InvokeKind.Static, MethodId(BigInteger, "valueOf", _, _), None, List(n: RVal.IntConst)) => n
    case LVal.StaticField(BigInteger, "ZERO") => RVal.IntConst(0)
    case LVal.StaticField(BigInteger, "ONE")  => RVal.IntConst(1)
    case LVal.StaticField(BigInteger, "TWO")  => RVal.IntConst(2)
    case LVal.StaticField(BigInteger, "TEN")  => RVal.IntConst(10)
    case inv @ RVal.Invoke(InvokeKind.Virtual, MethodId(BigInteger, name, _, _), Some(a), args) =>
      (name, args) match
        case ("add", List(b))      => RVal.Binop(liftValue(a), BinOp.Add, liftValue(b))
        case ("subtract", List(b)) => RVal.Binop(liftValue(a), BinOp.Sub, liftValue(b))
        case ("multiply", List(b)) => RVal.Binop(liftValue(a), BinOp.Mult, liftValue(b))
        case ("negate", Nil)       => RVal.Binop(RVal.IntConst(0), BinOp.Sub, liftValue(a))
        case _                     => liftInvoke(inv)
    case i: RVal.Invoke       => liftInvoke(i)
    case RVal.Binop(l, op, r) => RVal.Binop(liftValue(l), op, liftValue(r))
    case _                    => v
