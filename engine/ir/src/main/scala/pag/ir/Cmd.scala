package pag.ir

/** A command of the source IR (implementation_strategy.md §5.1). A command does
  * not know its own location: its position is its index in `Method.body`.
  */
sealed trait Cmd

object Cmd:
  final case class Assign(target: LVal, source: RVal) extends Cmd

  /** Jump to body index `target` when `cond` holds; otherwise fall through. */
  final case class Goto(cond: RVal, target: Int) extends Cmd:
    require(target >= 0, s"negative jump target $target")

  case object Nop extends Cmd
  final case class Return(value: Option[RVal]) extends Cmd

  /** A call whose result is discarded. */
  final case class InvokeStmt(call: RVal.Invoke) extends Cmd

  /** Disabled in v1. */
  case object Throw extends Cmd
