package pag.core

import pag.ir.*

/** The entry method as a CFG, and the pre location of each reach(id) call:
  * lowering is where reach calls disappear, so it records where they were (§5.8).
  * Every site, in program order: ids are unique only when the profile check
  * ran, and a translation records what is there either way.
  */
final case class Lowered(cfg: Cfg, reachSites: Map[BigInt, List[Loc.AppLoc]])

/** Lowering: source IR to a transition relation over pre/post locations, one
  * row of implementation_strategy.md §5.3's table per command. Branches become
  * assumes, calls become Call, and a domain never sees dispatch or control flow.
  * Trust base (§2).
  */
object Lowering:

  def lower(program: Program): Lowered =
    val m = program.methods.find(_.id == program.entryMethod).get // Program guarantees it exists
    require(m.body.nonEmpty, s"${m.id} has no commands")
    val entry = Loc.InternalMethodEntry(m.id)
    val exit = Loc.InternalMethodExit(m.id)
    def pre(i: Int) = Loc.AppLoc(m.id, i, true)
    def post(i: Int) = Loc.AppLoc(m.id, i, false)
    def next(i: Int): Loc =
      if i + 1 < m.body.size then pre(i + 1)
      else throw IllegalStateException(s"${m.id}: command $i falls through past the last command")

    val edges = List.newBuilder[Transition]
    edges += Transition(entry, Step.Skip, pre(0))
    for (c, i) <- m.body.zipWithIndex do
      def effect(s: Step) = edges += Transition(pre(i), s, post(i))
      def thenTo(to: Loc) = edges += Transition(post(i), Step.Skip, to)
      c match
        case Cmd.Assign(_: LVal.Local, LVal.Param(0, _)) => // main's args binding: never read (§5.2)
          effect(Step.Skip); thenTo(next(i))
        case Cmd.Assign(t, RVal.Invoke(_, f, recv, args)) =>
          val target = t match
            case l: LVal.Local => l
            case _             => throw IllegalStateException(s"${m.id}, command $i: a call assigned to $t")
          effect(Step.Call(Some(target), f, recv.toList ++ args)); thenTo(next(i))
        case Cmd.Assign(t, e) =>
          effect(Step.Assign(t, e)); thenTo(next(i))
        case ReachCall(_) =>
          effect(Step.Skip); thenTo(next(i))
        case Cmd.InvokeStmt(RVal.Invoke(_, f, recv, args)) =>
          effect(Step.Call(None, f, recv.toList ++ args)); thenTo(next(i))
        case Cmd.Nop =>
          effect(Step.Skip); thenTo(next(i))
        case Cmd.Goto(RVal.BoolConst(true), t) =>
          effect(Step.Skip); thenTo(pre(t))
        case Cmd.Goto(cond, t) =>
          effect(Step.Skip)
          edges += Transition(post(i), Step.Assume(cond), pre(t))
          edges += Transition(post(i), Step.Assume(negate(cond)), next(i))
        case Cmd.Return(_) =>
          effect(Step.Skip); thenTo(exit)
        case Cmd.Throw => // outside v1's profile; execution ends, so post(i) has no successor
          effect(Step.Skip)

    // groupMap keeps each id's sites in program order
    val sites = m.body.toList.zipWithIndex.collect { case (ReachCall(id), i) => id -> pre(i) }.groupMap(_._1)(_._2)
    Lowered(Cfg(edges.result(), entry, exit), sites)

  /** ¬(a OP b) as another comparison: the operator flips, the operands stay (§5.1). */
  private def negate(cond: RVal): RVal = cond match
    case RVal.Binop(l, op, r) =>
      val flipped = op match
        case BinOp.Lt => BinOp.Ge
        case BinOp.Ge => BinOp.Lt
        case BinOp.Le => BinOp.Gt
        case BinOp.Gt => BinOp.Le
        case BinOp.Eq => BinOp.Ne
        case BinOp.Ne => BinOp.Eq
        case BinOp.Add | BinOp.Sub | BinOp.Mult =>
          throw IllegalStateException(s"branch condition $cond is not a comparison")
      RVal.Binop(l, flipped, r)
    case _ => throw IllegalStateException(s"branch condition $cond is not a comparison")

  /** A `reach(id)` call statement, matched by its id (§5.8). */
  private object ReachCall:
    def unapply(c: Cmd): Option[BigInt] = c match
      case Cmd.InvokeStmt(RVal.Invoke(InvokeKind.Static, f, None, List(RVal.IntConst(id))))
          if f.qualifiedName == "pag.probe.Reach.reach" => Some(id)
      case _ => None
