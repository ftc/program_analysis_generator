package pag.campaign

import io.bullet.borer.Codec
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

import pag.results.{CheckResult, Envelope}
import pag.results.Codecs.given
import pag.campaign.ChatClient.given
import pag.campaign.AttemptRecord.given // the parts unchanged since schema 1

/** The flat record shape written before 2026-10-09 (schema 1), frozen: the
  * committed results in `results/` are in it. It is only read, then converted to
  * the current `AttemptRecord`; nothing writes it. Do not change these types.
  * Several of their Options are correlated, which the current shape rules out;
  * a combination the code that wrote them could not produce is refused.
  */
object AttemptRecordV1:

  final case class Attempt(
      envelope: Envelope,
      campaign: String,
      attempt: String,
      sample: Int,
      startedAt: String,
      elapsedMs: Long,
      agent: AgentRecord,
      serverModels: Option[String],
      prompt: PromptRecord,
      reply: Option[ChatReply],
      failure: Option[ChatFailure],
      files: Option[FilesRecord],
      build: Option[Build],
      targets: List[Target],
      summary: Summary
  )

  final case class ChatFailure(message: String, status: Option[Int], body: Option[String], tries: Int)

  final case class Build(
      compiled: Boolean,
      compileLog: String,
      testsRun: Int,
      testFailures: Int,
      testErrors: Int,
      testLog: String,
      elapsedMs: Long,
      testsCompiled: Option[Boolean] = None,
      compileTries: Option[Int] = None,
      compileTimedOut: Option[Boolean] = None,
      earlierCompileLogs: Option[List[String]] = None
  )

  final case class Target(probe: String, reach: Int, reachable: Boolean, rung: Int, cell: String,
      exitCode: Option[Int], check: Option[CheckResult], stderr: String, timedOut: Boolean, elapsedMs: Long)

  final case class Summary(outOfTokens: Boolean, files: Int, builds: Boolean, testsRun: Int, testsFailed: Int,
      loads: Boolean, cells: List[String], proved: Int, unsound: Boolean, testsCompiled: Option[Boolean] = None)

  given Codec[ChatFailure] = deriveCodec[ChatFailure]
  given Codec[Build] = deriveCodec[Build]
  given Codec[Target] = deriveCodec[Target]
  given Codec[Summary] = deriveCodec[Summary]
  given Codec[Attempt] = deriveCodec[Attempt]

  /** Schema 1 evaluation failures were kept among the files' problems, with this prefix. */
  private val EvaluationPrefix = "evaluation: "

  /** The current shape. A combination schema 1's writer could not produce is an error, never guessed at. */
  def convert(a: Attempt): Either[String, AttemptRecord] =
    val exchange: Either[String, Exchange] = (a.reply, a.failure, a.files) match
      case (None, Some(f), None) => failure(f).map(Exchange.NoReply(_))
      case (Some(r), None, Some(files)) =>
        val (evaluationErrors, problems) = files.problems.partition(_.startsWith(EvaluationPrefix))
        a.build.fold[Either[String, Option[BuildRecord]]](Right(None))(b =>
          build(b, a.targets, evaluationErrors.map(_.stripPrefix(EvaluationPrefix))).map(Some(_))
        ).map(Exchange.Replied(r, files.copy(problems = problems), _))
      case _ => Left("schema 1: expected a reply with files, or a failure without, and found neither")
    exchange.map(e => AttemptRecord(1, a.envelope, a.campaign, a.attempt, a.sample, a.startedAt, a.elapsedMs, a.agent,
      a.serverModels, a.prompt, Conversation(Nil, LastRound(a.prompt.messages, e))))

  private def failure(f: ChatFailure): Either[String, pag.campaign.ChatFailure] = (f.status, f.body) match
    case (None, None)       => Right(pag.campaign.ChatFailure.NoResponse(f.message, f.tries))
    case (Some(s), Some(b)) => Right(pag.campaign.ChatFailure.BadResponse(f.message, s, b, f.tries))
    case _                  => Left("schema 1: a failure with a status but no body, or a body but no status")

  private def build(b: Build, targets: List[Target], evaluationErrors: List[String]): Either[String, BuildRecord] =
    // Before retries there was one try, and a try killed at the limit ended its log so (Build.log).
    val last = CompileTry(b.compileLog, b.compileTimedOut.getOrElse(b.compileLog.endsWith("(killed at the time limit)")))
    val earlier = b.earlierCompileLogs.getOrElse(Nil).map(log => CompileTry(log, log.endsWith("(killed at the time limit)")))
    if !b.compiled then
      if targets.isEmpty then Right(BuildRecord.Failed(last, earlier)) else Left("schema 1: targets without a build")
    else
      val tests = b.testsCompiled match
        case Some(false) => Tests.DidNotCompile(b.testLog)
        case Some(true)  => Tests.Ran(b.testsRun, b.testFailures, b.testErrors, b.testLog)
        case None        => Tests.CompileNotRecorded(b.testsRun, b.testFailures, b.testErrors, b.testLog)
      val evaluation = evaluationErrors match
        case Nil          => targets.map(target).sequence.map(EvaluationRecord.Evaluated(_))
        case List(e)      => if targets.isEmpty then Right(EvaluationRecord.Failed(e)) else Left("schema 1: targets and an evaluation error")
        case _            => Left("schema 1: more than one evaluation error")
      evaluation.map(BuildRecord.Compiled(last, earlier, tests, _))

  private def target(t: Target): Either[String, TargetRecord] =
    val run = (t.timedOut, t.exitCode) match
      case (true, None)        => if t.check.isEmpty then Right(TargetRun.Killed) else Left("schema 1: a killed target with a result")
      case (false, Some(code)) => Right(TargetRun.Exited(code, t.check))
      case _                   => Left("schema 1: a target killed with an exit code, or not killed without one")
    run.map(TargetRecord(t.probe, t.reach, t.reachable, t.rung, _, t.stderr, t.elapsedMs))

  extension [A](xs: List[Either[String, A]])
    private def sequence: Either[String, List[A]] =
      xs.foldRight[Either[String, List[A]]](Right(Nil))((x, acc) => x.flatMap(a => acc.map(a :: _)))
