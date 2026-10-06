package pag.cli

import java.nio.file.Path

import pag.core.{AnalysisResult, Profile}
import pag.harness.JvmRunResult
import pag.results.{AnalysisSummary, CheckResult, Envelope, Outcome, Reachable, RunSummary}

/** A check as a `CheckResult` (implementation_strategy.md §13): `core`'s result
  * reduced to the data that crosses to the driver, without the invariant map.
  */
object CheckRecord:

  def apply(
      classes: Path,
      q: Reachable,
      r: AnalysisResult[?],
      inputs: List[BigInt],
      run: JvmRunResult,
      runMs: Long,
      outcome: Outcome
  ): CheckResult =
    CheckResult(
      Envelope.current(Profile.BigintMainV1.name),
      q,
      classes.toString,
      inputs,
      AnalysisSummary(r.verdict, r.iterations, r.unexplored, r.elapsedMs,
        r.certification.map(_.edges), r.certification.map(_.uncertified.size)),
      RunSummary(run.reached.toList, run.exitCode, run.timedOut, runMs),
      outcome
    )
