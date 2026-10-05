package pag.core

import pag.ir.{Loc, Transition}

/** Why a search was incomplete (implementation_strategy.md §7). All three stop
  * it, so exactly one can fire.
  */
enum Incomplete:
  case IterationLimit(at: Int)
  case Deadline(afterMs: Long)

  /** `op` threw (any `Throwable`) or returned null; the null case carries a
    * `NullPointerException` naming `op`.
    */
  case DomainFailure(op: String, error: Throwable)

/** The search's budget (§7, §11). An iteration is one transition processed. */
final case class Limits(iterations: Int = 10_000, deadlineMs: Long = 60_000):
  require(iterations > 0, s"iteration limit $iterations")
  require(deadlineMs > 0, s"deadline $deadlineMs ms")

/** Observes the analysis without taking part in it (§8). Called unconditionally;
  * `NullRecorder` discards. States are opaque here, as they are to the engine.
  *
  * Minimal until Phase 4.5 builds the derivation graph, which may reshape it;
  * the call sites are what matters now, so they are never retrofitted.
  */
trait Recorder:
  /** `t` was processed: `post` was its `to` state, `contribution` what it gave `t.from`. */
  def transferred(t: Transition, post: Any, contribution: Any): Unit

  /** `loc`'s state grew to `state`, by widening if `widened`. */
  def updated(loc: Loc, state: Any, widened: Boolean): Unit

  /** `t` was still in the worklist when the search stopped. */
  def unexplored(t: Transition): Unit

object NullRecorder extends Recorder:
  def transferred(t: Transition, post: Any, contribution: Any): Unit = ()
  def updated(loc: Loc, state: Any, widened: Boolean): Unit = ()
  def unexplored(t: Transition): Unit = ()
