package pag.harness

import pag.core.Lowered
import pag.ir.*
import pag.probe.Inputs

/** How an IR interpreter run ended. */
enum Ended:
  /** Reached the method's exit. */
  case Exit
  /** randInt was called after the inputs ran out, as Rand throws (§5.6). */
  case InputsExhausted(at: Loc)
  /** No edge was enabled: execution stopped here, as after a throw. */
  case Stuck(at: Loc)
  /** Took this many transitions without ending. */
  case StepLimit(steps: Int)

/** What an IR interpreter run did: every location in order, the reach ids passed in
  * order (a reach inside a loop repeats), the final values, and how it ended.
  */
final case class IrRun(
    visited: Vector[Loc],
    reached: Vector[BigInt],
    values: Map[LVal.Local, BigInt],
    ended: Ended
)

/** The IR interpreter: runs a lowered CFG directly, every value a BigInt
  * (implementation_strategy.md §9). It runs the IR a domain analyses, which the
  * JVM never sees, so agreement with the JVM run checks the translation. Covers
  * what lowering emits under the v1 profile; anything else is an engine bug.
  * Trust base (§2).
  */
object IrInterpreter:

  val DefaultStepLimit: Int = 1_000_000

  def run(lowered: Lowered, inputs: Inputs, stepLimit: Int = DefaultStepLimit): IrRun =
    val edges = lowered.cfg.transitions.groupBy(_.from)
    val reachAt: Map[Loc, BigInt] = lowered.reachSites.map((id, loc) => loc -> id)
    val visited = Vector.newBuilder[Loc]
    val reached = Vector.newBuilder[BigInt]
    var values = Map.empty[LVal.Local, BigInt]
    var at: Loc = lowered.cfg.init
    var steps = 0

    def value(v: RVal): BigInt = v match
      case RVal.IntConst(n)             => n
      case l: LVal.Local                => values.getOrElse(l, fail(s"$at: ${l.name} read before it is assigned"))
      case RVal.Binop(l, BinOp.Add, r)  => value(l) + value(r)
      case RVal.Binop(l, BinOp.Sub, r)  => value(l) - value(r)
      case RVal.Binop(l, BinOp.Mult, r) => value(l) * value(r)
      case other                        => fail(s"$at: cannot evaluate $other")

    def holds(cond: RVal): Boolean = cond match
      case RVal.BoolConst(b)           => b
      case RVal.Binop(l, BinOp.Lt, r)  => value(l) < value(r)
      case RVal.Binop(l, BinOp.Le, r)  => value(l) <= value(r)
      case RVal.Binop(l, BinOp.Gt, r)  => value(l) > value(r)
      case RVal.Binop(l, BinOp.Ge, r)  => value(l) >= value(r)
      case RVal.Binop(l, BinOp.Eq, r)  => value(l) == value(r)
      case RVal.Binop(l, BinOp.Ne, r)  => value(l) != value(r)
      case other                       => fail(s"$at: cannot test $other")

    def finish(ended: Ended) = IrRun(visited.result(), reached.result(), values, ended)

    while true do
      visited += at
      reachAt.get(at).foreach(reached += _)
      if at == lowered.cfg.exit then return finish(Ended.Exit)
      if steps >= stepLimit then return finish(Ended.StepLimit(steps))
      val enabled = edges.getOrElse(at, Nil).filter {
        case Transition(_, Step.Assume(cond), _) => holds(cond)
        case _                                   => true
      }
      enabled match
        case Nil => return finish(Ended.Stuck(at))
        case List(t) =>
          t.step match
            case Step.Assign(x: LVal.Local, e) => values += x -> value(e)
            case Step.Call(Some(x), f, Nil) if f.qualifiedName == "pag.probe.Rand.randInt" =>
              try values += x -> BigInt(inputs.next())
              catch case _: IllegalStateException => return finish(Ended.InputsExhausted(at))
            case Step.Assume(_) | Step.Skip => ()
            case other                      => fail(s"$at: cannot execute $other")
          steps += 1
          at = t.to
        case many => fail(s"$at: ${many.size} edges enabled; a lowered program has one")
    throw AssertionError("unreachable")

  private def fail(msg: String): Nothing = throw IllegalStateException(s"IR interpreter: $msg")
