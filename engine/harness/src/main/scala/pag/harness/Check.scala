package pag.harness

import pag.core.{Incomplete, Reachable, Verdict}

/** What a reachability check concludes (implementation_strategy.md §9). */
enum Outcome:
  /** Refuted, and the run printed `REACHED-<id>`: the domain is unsound. */
  case Unsound

  /** Nothing contradicted: not refuted, or refuted and this run does not reach the target. */
  case Consistent

  /** The analysis did not finish, so the run judges nothing. */
  case NoVerdict(why: Incomplete)

/** The reachability check's judgment (§9). Trust base (§2, the marker check):
  * the only place a run can overturn a refutation. Only the queried id counts,
  * and a marker printed before a timeout or crash still counts, since the JVM run
  * keeps stdout in a file (§9).
  */
object Check:

  def judge(verdict: Verdict, reached: Seq[BigInt], q: Reachable): Outcome = verdict match
    case Verdict.Refuted if reached.contains(BigInt(q.id)) => Outcome.Unsound
    case Verdict.Refuted | Verdict.Alarm                   => Outcome.Consistent
    case Verdict.Inconclusive(why)                         => Outcome.NoVerdict(why)
