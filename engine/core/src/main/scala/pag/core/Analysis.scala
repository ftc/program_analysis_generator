package pag.core

import pag.ir.{Loc, Transition}
import pag.results.{Incomplete, Query, Verdict}

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

  /** `[edge-inductive]` failed on `t` (§7). */
  def uncertified(t: Transition): Unit

object NullRecorder extends Recorder:
  def transferred(t: Transition, post: Any, contribution: Any): Unit = ()
  def updated(loc: Loc, state: Any, widened: Boolean): Unit = ()
  def unexplored(t: Transition): Unit = ()
  def uncertified(t: Transition): Unit = ()

/** The verdict, the invariant map, and what the search cost (§7).
  * `certification` is absent when the certifier did not run: the search stopped
  * early, or a domain call failed. `states` is the map the certifier checked, or
  * what was computed before the search stopped; a location absent from it is ⊥.
  */
final case class AnalysisResult[S](
    verdict: Verdict,
    certification: Option[Certification],
    states: Map[Loc, S],
    iterations: Int,
    unexplored: Int,
    elapsedMs: Long,
    widenedAt: Set[Loc]
):
  /** For reading in a debugger: the verdict, then one location per line in
    * program order, each state printed by its own `toString`.
    */
  override def toString: String =
    def order(l: Loc) = l match
      case Loc.InternalMethodEntry(_)    => (0, 0, 0)
      case Loc.AppLoc(_, i, isPre)       => (1, i, if isPre then 0 else 1)
      case Loc.InternalMethodExit(_)     => (2, 0, 0)
    val lines = states.toList.sortBy((l, _) => order(l)).map((l, s) => f"  ${pag.ir.Pretty.loc(l)}%-9s $s")
    (s"$verdict · $iterations iterations · absent locations are ⊥" :: lines).mkString("\n")

object Analysis:

  /** Resolve `q`, compute, then certify (§6, §7). Resolution happens here, and
    * the one resolved set goes to both stages, so no caller can hand the
    * certifier targets that differ from the question asked. `Left` is a query
    * the program cannot answer (§6), a usage error. `resolver` is a parameter so
    * tests can break it.
    */
  def analyze[S](
      d: pag.api.Domain[S],
      lowered: Lowered,
      q: Query,
      limits: Limits = Limits(),
      recorder: Recorder = NullRecorder,
      clock: () => Long = () => System.currentTimeMillis(),
      resolver: Option[ControlFlowResolver] = None
  ): Either[String, AnalysisResult[S]] =
    QueryResolver.resolve(q, lowered).map { targets =>
      val cfg = lowered.cfg
      val computed = Worklist.compute(d, resolver.getOrElse(ControlFlowResolver(cfg)), targets, limits, recorder, clock)
      def result(v: Verdict, c: Option[Certification]) =
        AnalysisResult(v, c, computed.states, computed.iterations, computed.unexplored, computed.elapsedMs,
          computed.widenedAt)
      computed.stopped match
        case Some(why) => result(Verdict.Inconclusive(why), None)
        case None =>
          Certifier.certify(d, cfg, targets, computed.states, recorder) match
            case Left(failure) => result(Verdict.Inconclusive(failure), None)
            case Right(c)      => result(if c.refutes then Verdict.Refuted else Verdict.Alarm, Some(c))
    }
