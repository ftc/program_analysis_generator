package pag.results

/** Who produced a record (implementation_strategy.md §13): the commit pag was
  * built from, whether the tree had uncommitted changes then (so the commit alone
  * does not identify the code), and the language profile.
  */
final case class Envelope(commit: String, dirty: Boolean, profile: String)

object Envelope:
  /** The envelope for records this build of pag writes. */
  def current(profile: String): Envelope = Envelope(BuildInfo.commit, BuildInfo.dirty, profile)

/** What the analysis concluded and what it cost, without the invariant map. */
final case class AnalysisSummary(
    verdict: Verdict,
    iterations: Int,
    unexplored: Int,
    elapsedMs: Long,
    edges: Option[Int], // certification: transitions checked; None if the certifier did not run
    uncertified: Option[Int] // and how many failed [edge-inductive]
)

/** The JVM run: the `reach` ids it printed, in order, and how it ended. */
final case class RunSummary(reached: List[BigInt], exitCode: Int, timedOut: Boolean, elapsedMs: Long)

/** One reachability check (§9), as `pag check --json` prints it. The program is
  * named by its classes directory: `pag` never sees the source, so a driver
  * recording a rejection adds the `.java` file itself (§3).
  */
final case class CheckResult(
    envelope: Envelope,
    query: Reachable,
    classes: String,
    inputs: List[BigInt],
    analysis: AnalysisSummary,
    run: RunSummary,
    outcome: Outcome
)
