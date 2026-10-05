package pag.core

import java.math.BigInteger
import pag.api
import pag.api.Domain
import pag.ir.*

/** Small domains and hand-built CFGs shared by the certifier and analysis suites. */
object TestDomains:

  /** May some state here still reach the target? */
  enum Flag:
    case No, Maybe

  /** Sound: it refutes only an `assume` comparing two constants that is false, and
    * passes every other state through unchanged, so it never excludes a run.
    */
  class FlagDomain extends Domain[Flag]:
    def name = "flag"
    def top = Flag.Maybe
    def bottom = Flag.No
    def isBottom(s: Flag) = s == Flag.No
    def entails(a: Flag, b: Flag) = a == Flag.No || b == Flag.Maybe
    def join(a: Flag, b: Flag) = if a == Flag.Maybe || b == Flag.Maybe then Flag.Maybe else Flag.No
    def widen(a: Flag, b: Flag) = join(a, b)
    def transfer(step: api.Step, post: Flag) = step match
      case a: api.Step.Assume =>
        a.cond match
          case c: api.RVal.Binop =>
            (c.l, c.r) match
              case (l: api.RVal.IntConst, r: api.RVal.IntConst) => if holds(l.v, c.op, r.v) then post else Flag.No
              case _                                            => post
          case _ => post
      case _ => post

  def holds(l: BigInteger, op: api.BinOp, r: BigInteger): Boolean =
    val c = l.compareTo(r)
    op match
      case api.BinOp.Lt => c < 0
      case api.BinOp.Le => c <= 0
      case api.BinOp.Gt => c > 0
      case api.BinOp.Ge => c >= 0
      case api.BinOp.Eq => c == 0
      case api.BinOp.Ne => c != 0
      case _            => true

  val Main: MethodId = MethodId("Probe", "main", List(JType.ArrayOf(JType.Ref("java.lang.String"))), JType.Void)
  val x: LVal.Local = LVal.Local("x", JType.Ref("java.math.BigInteger"))
  def L(k: Int): Loc.AppLoc = Loc.AppLoc(Main, k, true)
  def skip(a: Int, b: Int): Transition = Transition(L(a), Step.Skip, L(b))
  def assign(a: Int, b: Int): Transition = Transition(L(a), Step.Assign(x, RVal.IntConst(a)), L(b))
  def guardEdge(a: Int, l: Int, op: BinOp, r: Int, b: Int): Transition =
    Transition(L(a), Step.Assume(RVal.Binop(RVal.IntConst(l), op, RVal.IntConst(r))), L(b))

  /** A CFG whose init is L(0). */
  def cfg(ts: Transition*): Cfg = Cfg(ts.toList, L(0), L(99))
