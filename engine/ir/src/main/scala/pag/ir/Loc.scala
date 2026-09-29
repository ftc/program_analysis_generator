package pag.ir

/** Where an abstract state lives (implementation_strategy.md §5.3). Mirrors
  * Historia's Loc; the Internal prefix leaves room for Callback and Callin
  * locations when framework modelling returns.
  */
sealed trait Loc

object Loc:

  /** Method entry, before its first command. Historia's InternalMethodInvoke. */
  final case class InternalMethodEntry(method: MethodId) extends Loc

  /** Just before (isPre) or just after command `index` of a method. */
  final case class AppLoc(method: MethodId, index: Int, isPre: Boolean) extends Loc:
    require(index >= 0, s"negative command index $index")

  /** Method exit, after any Return. Historia's InternalMethodReturn. */
  final case class InternalMethodExit(method: MethodId) extends Loc
