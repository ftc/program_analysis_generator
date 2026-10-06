package pag.cli

import java.nio.file.Path

import pag.core.{AnalysisResult, Incomplete, Reachable, Verdict}
import pag.harness.{JvmRunResult, Outcome}

/** What `pag check` prints (implementation_strategy.md §11): one line for the
  * analysis, one for the JVM run, then the outcome. `pag analyze` shows the map.
  */
object CheckReport:

  def lines(
      classes: Path,
      q: Reachable,
      r: AnalysisResult[?],
      inputs: List[BigInt],
      run: JvmRunResult,
      runMs: Long,
      outcome: Outcome
  ): List[String] =
    val shownInputs = inputs.mkString("[", ", ", "]")
    val reached = run.reached.contains(BigInt(q.id))
    val marker = if reached then s"REACHED-${q.id}" else s"reach(${q.id}) not reached"
    val how = List(
      Option.when(run.timedOut)("timed out"),
      Option.when(!run.timedOut && run.exitCode != 0)(s"exit ${run.exitCode}")
    ).flatten.map(" · " + _).mkString
    val conclusion = outcome match
      case Outcome.Unsound =>
        List(s"UNSOUND — the domain refuted reach(${q.id}), but the program reaches it",
          s"reaching run   $classes  inputs $shownInputs")
      case Outcome.Consistent =>
        (r.verdict, reached) match
          case (Verdict.Alarm, true) => List(s"CONSISTENT — an alarm, and this run reaches reach(${q.id}): the alarm is real")
          case (Verdict.Alarm, false) => List(s"CONSISTENT — an alarm; this run does not reach reach(${q.id})")
          case _                      => List(s"CONSISTENT — refuted, and this run does not reach reach(${q.id})")
      case Outcome.NoVerdict(why) =>
        List(s"NO VERDICT — the analysis was inconclusive (${incomplete(why)}); the run judges nothing")
    List(
      f"analysis    ${verdict(r.verdict)}%-18s ${r.iterations} iterations · ${r.elapsedMs}ms",
      f"execution   $marker%-18s inputs $shownInputs · ${runMs}ms$how",
      ""
    ) ++ conclusion

  private def verdict(v: Verdict): String = v match
    case Verdict.Refuted                                   => "REFUTED"
    case Verdict.Alarm                                     => "ALARM"
    case Verdict.Inconclusive(_: Incomplete.DomainFailure) => "DOMAIN FAILURE"
    case Verdict.Inconclusive(_)                           => "INCONCLUSIVE"

  private def incomplete(i: Incomplete): String = i match
    case Incomplete.IterationLimit(n)    => s"iteration limit $n"
    case Incomplete.Deadline(ms)         => s"deadline after ${ms}ms"
    case Incomplete.DomainFailure(op, e) => s"$op threw ${e.getClass.getName}"
