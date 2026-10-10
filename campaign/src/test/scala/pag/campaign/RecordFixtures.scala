package pag.campaign

import pag.results.{AnalysisSummary, CheckResult, Envelope, ErrorInfo, Incomplete, Outcome, Reachable, RunSummary, Verdict}

/** Attempt records for tests of what reads them (the report, the status view).
  * Cells and summaries are derived from the record, so a test asks for the cells
  * it wants and gets target results that really produce them.
  */
object RecordFixtures:

  def check(verdict: Verdict): CheckResult =
    CheckResult(Envelope("test", false, "bigint-main-v1"), Reachable(1), "classes", Nil,
      AnalysisSummary(verdict, 1, 0, 1, None, None), RunSummary(Nil, 0, false, 1), Outcome.Consistent)

  /** How pag ended so that the target's cell is `cell` (a reachable target for ✗). */
  def run(cell: String): TargetRun = cell match
    case "R" | "✗" => TargetRun.Exited(0, Some(check(Verdict.Refuted)))
    case "A"       => TargetRun.Exited(0, Some(check(Verdict.Alarm)))
    case "I"       => TargetRun.Exited(4, Some(check(Verdict.Inconclusive(Incomplete.IterationLimit(1)))))
    case "E"       => TargetRun.Exited(5, Some(check(Verdict.Inconclusive(
        Incomplete.DomainFailure("transfer", ErrorInfo("java.lang.RuntimeException", None, ""))))))
    case "H"       => TargetRun.Killed
    case _         => TargetRun.Exited(1, None)

  /** `base`, a record with a reply, made into an evaluated attempt: four files, a build, these tests, these cells. */
  def evaluated(base: AttemptRecord, cells: List[String], probes: List[String], reachable: (String, String) => Boolean,
      tests: Tests = Tests.Ran(10, 1, 0, "")): AttemptRecord =
    val targets = probes.zip(cells).map((p, c) => TargetRecord(p, 1, reachable(p, c), 0, run(c), "", 1))
    val build = BuildRecord.Compiled(CompileTry("", false), Nil, tests, EvaluationRecord.Evaluated(targets))
    withExchange(base) { (reply, files, _) =>
      Exchange.Replied(reply, files.copy(written = List("a", "b", "c", "d")), Some(build))
    }

  /** `base` with its reply's exchange replaced; `base` must have a reply. */
  def withExchange(base: AttemptRecord)(f: (ChatReply, FilesRecord, Option[BuildRecord]) => Exchange): AttemptRecord =
    base.exchange match
      case Exchange.Replied(reply, files, build) =>
        base.copy(conversation = base.conversation.copy(last = base.conversation.last.copy(exchange = f(reply, files, build))))
      case Exchange.NoReply(_)                   => throw IllegalArgumentException("the base record has no reply")
