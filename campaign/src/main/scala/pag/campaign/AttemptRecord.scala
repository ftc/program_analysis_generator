package pag.campaign

import io.bullet.borer.Codec
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

import pag.results.{CheckResult, Envelope, Incomplete, Verdict}
import pag.results.Codecs.given
import pag.campaign.ChatClient.given // ChatMessage's codec, defined once

/** Everything one attempt produced (experiments.md E1), written as
  * `results/<campaign>/<attempt>/attempt.json`. Shaped so that only combinations
  * the pipeline can produce are representable: each stage, from reply to files
  * to build to tests and evaluation, holds the next, and an Option left has one
  * reason only. Records written before 2026-10-09 have the flat schema 1
  * (`AttemptRecordV1`) and are converted on reading.
  *
  * Record types have no default values: Borer leaves a field equal to its
  * default out of the encoding, so a record could silently lose it. Config types
  * (`AgentConfig`) have defaults for convenience, which is why the agent is
  * copied into `AgentRecord` here.
  */
final case class AttemptRecord(
    schema: Int, // AttemptRecord.Schema when written; 1 when converted from schema 1
    envelope: Envelope,
    campaign: String,
    attempt: String,
    sample: Int,
    startedAt: String, // ISO-8601, UTC
    elapsedMs: Long,
    agent: AgentRecord,
    serverModels: Option[String], // the server's own /v1/models report, raw; None: it did not answer with HTTP 200
    prompt: PromptRecord,
    exchange: Exchange
):
  def reply: Option[ChatReply] = exchange match
    case Exchange.Replied(r, _, _) => Some(r)
    case Exchange.NoReply(_)       => None

  def failure: Option[ChatFailure] = exchange match
    case Exchange.NoReply(f) => Some(f)
    case _                   => None

  def files: Option[FilesRecord] = exchange match
    case Exchange.Replied(_, f, _) => Some(f)
    case _                         => None

  def build: Option[BuildRecord] = exchange match
    case Exchange.Replied(_, _, b) => b
    case _                         => None

  /** Evaluated targets, in corpus order; empty unless the domain was built and evaluated. */
  def targets: List[TargetRecord] = build match
    case Some(BuildRecord.Compiled(_, _, _, EvaluationRecord.Evaluated(ts))) => ts
    case _                                                             => Nil

  /** Table 1's row, before inspection (experiments.md), derived: never stored, so it cannot disagree. */
  def summary: Summary =
    val (run, failed, compiled) = build match
      case Some(BuildRecord.Compiled(_, _, t, _)) => t match
          case Tests.Ran(r, f, e, _)                => (r, f + e, Some(true))
          case Tests.DidNotCompile(_)               => (0, 0, Some(false))
          case Tests.CompileNotRecorded(r, f, e, _) => (r, f + e, None)
      case _ => (0, 0, None)
    Summary(
      reply.exists(_.finishReason.contains("length")),
      files.fold(0)(_.written.size),
      build.exists(_.compiled),
      run,
      failed,
      targets.exists(_.check.isDefined), // pag printed a result only if it loaded the domain
      targets.map(_.cell),
      targets.count(t => t.refuted && !t.reachable),
      targets.exists(t => t.refuted && t.reachable),
      compiled
    )

/** How far the exchange with the model got. */
enum Exchange:
  case NoReply(failure: ChatFailure)
  // build None: no files were written from the reply, so there was nothing to build.
  case Replied(reply: ChatReply, files: FilesRecord, build: Option[BuildRecord])

/** One compile try: its Gradle log, and whether it was killed at the time limit. */
final case class CompileTry(log: String, timedOut: Boolean)

/** A build: the try that decided it, the earlier tries (retries, oldest first), and what followed. */
enum BuildRecord:
  case Failed(last: CompileTry, earlier: List[CompileTry])
  case Compiled(last: CompileTry, earlier: List[CompileTry], tests: Tests, evaluation: EvaluationRecord)

  def compiled: Boolean = this match
    case _: Compiled => true
    case _: Failed   => false

  def last: CompileTry
  def earlier: List[CompileTry]

/** The domain's own JUnit tests. */
enum Tests:
  case DidNotCompile(log: String) // the test compile's log
  case Ran(run: Int, failures: Int, errors: Int, log: String) // failures: assertions; errors: tests that threw
  // Only in records from before 2026-10-09, which did not record whether the tests compiled; 0 run may mean either.
  case CompileNotRecorded(run: Int, failures: Int, errors: Int, log: String)

/** The domain on the corpus. */
enum EvaluationRecord:
  case Failed(error: String) // the corpus could not be run at all, e.g. a probe did not compile
  case Evaluated(targets: List[TargetRecord]) // one per target, in corpus order

/** How `pag check` ended on one target. */
enum TargetRun:
  case Killed // at the wall-clock limit
  // check None: pag printed no result that decodes (an exit code other than 0, 3, 4 or 5, such as failing to load
  // the domain, or something else printed).
  case Exited(code: Int, check: Option[CheckResult])

/** Table 1 summary row; derived by `AttemptRecord.summary`, never stored. */
final case class Summary(
    outOfTokens: Boolean, // the reply stopped at the token budget (finish_reason "length")
    files: Int,
    builds: Boolean,
    testsRun: Int,
    testsFailed: Int, // failures plus errors
    loads: Boolean,
    cells: List[String], // one per target, in corpus order
    proved: Int,
    unsound: Boolean,
    testsCompiled: Option[Boolean] // None: nothing built, the domain did not compile, or not recorded (before 2026-10-09)
)

final case class AgentRecord(
    baseUrl: String,
    model: String,
    apiKeyEnv: Option[String], // None: no Authorization header was sent
    temperature: Double,
    topP: Option[Double], // this and the next three, None: not sent, so the server's default applied
    topK: Option[Int],
    minP: Option[Double],
    presencePenalty: Option[Double],
    thinking: Option[Boolean], // None: not sent, so the chat template's default applied
    maxTokens: Option[Int], // None: not sent, so the server's default applied
    timeoutSeconds: Int,
    retries: Int,
    source: SourceRecord
)

/** The model's provenance, copied from the config; each field None: not entered there. */
final case class SourceRecord(url: Option[String], file: Option[String], revision: Option[String], sha256: Option[String])

object AgentRecord:
  def of(a: AgentConfig): AgentRecord =
    AgentRecord(a.baseUrl, a.model, a.apiKeyEnv, a.temperature, a.topP, a.topK, a.minP, a.presencePenalty, a.thinking,
      a.maxTokens, a.timeoutSeconds, a.retries,
      SourceRecord(a.source.url, a.source.file, a.source.revision, a.source.sha256))

/** The full messages sent, not just their identity (implementation_strategy.md Phase 10). */
final case class PromptRecord(version: String, sha256: String, messages: List[ChatMessage])

final case class FilesRecord(written: List[String], ignoredBlocks: Int, problems: List[String])

final case class TargetRecord(
    probe: String,
    reach: Int,
    reachable: Boolean,
    rung: Int,
    run: TargetRun,
    stderr: String,
    elapsedMs: Long
):
  def check: Option[CheckResult] = run match
    case TargetRun.Exited(_, c) => c
    case TargetRun.Killed       => None

  def refuted: Boolean = check.exists(_.analysis.verdict == Verdict.Refuted)

  /** Table 1's cell (experiments.md): R refuted, A alarm, ✗ refuted a reachable
    * target, I inconclusive (did not converge: iteration limit or deadline),
    * E domain failure, H hung (killed at the wall-clock bound), – anything else.
    */
  def cell: String = run match
    case TargetRun.Killed => "H"
    case TargetRun.Exited(_, check) =>
      check.map(_.analysis.verdict) match
        case Some(Verdict.Refuted)                                   => if reachable then "✗" else "R"
        case Some(Verdict.Alarm)                                     => "A"
        case Some(Verdict.Inconclusive(_: Incomplete.DomainFailure)) => "E"
        case Some(Verdict.Inconclusive(_))                           => "I"
        case None                                                    => "–"

object AttemptRecord:
  /** The schema this code writes: 2 since 2026-10-09. */
  val Schema: Int = 2

  given Codec[SourceRecord] = deriveCodec[SourceRecord]
  given Codec[AgentRecord] = deriveCodec[AgentRecord]
  given Codec[PromptRecord] = deriveCodec[PromptRecord]
  given Codec[ChatReply] = deriveCodec[ChatReply]
  given Codec[ChatFailure] = deriveAllCodecs[ChatFailure]
  given Codec[FilesRecord] = deriveCodec[FilesRecord]
  given Codec[CompileTry] = deriveCodec[CompileTry]
  given Codec[TargetRun] = deriveAllCodecs[TargetRun]
  given Codec[TargetRecord] = deriveCodec[TargetRecord]
  given Codec[EvaluationRecord] = deriveAllCodecs[EvaluationRecord]
  given Codec[Tests] = deriveAllCodecs[Tests]
  given Codec[BuildRecord] = deriveAllCodecs[BuildRecord]
  given Codec[Exchange] = deriveAllCodecs[Exchange]
  given Codec[AttemptRecord] = deriveCodec[AttemptRecord]
