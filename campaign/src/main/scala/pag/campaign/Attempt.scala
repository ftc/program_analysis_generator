package pag.campaign

import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant

import io.bullet.borer.Json
import pag.results.Envelope

/** What an attempt needs besides the model: how to run pag, how to build a
  * domain, and what to judge it on.
  */
final case class Tools(pag: List[String], template: Path, api: Path, probeLib: Path, corpus: Path)

/** One attempt, end to end (implementation_strategy.md Phase 10): ask the model,
  * write the files its reply names, build them, run the domain's own tests,
  * evaluate it on the corpus, and write `attempt.json`. Each step that cannot run
  * is recorded as not run, never skipped silently; the record is written in every
  * case, last, and atomically, so its presence means the attempt finished.
  */
object Attempt:

  val Profile: String = "bigint-main-v1"

  def run(campaign: String, sample: Int, agent: AgentConfig, client: ChatClient, prompt: Prompt, tools: Tools,
      dir: Path, stage: String => Unit = _ => ()): AttemptRecord =
    val started = Instant.now()
    val attempt = dir.getFileName.toString
    Files.createDirectories(dir)
    val serverModels = client.models().toOption
    val exchange = round(prompt.messages, client, tools, dir.resolve("domain"), dir.resolve("work"), stage)
    val record = AttemptRecord(
      AttemptRecord.Schema,
      Envelope.current(Profile),
      campaign,
      attempt,
      sample,
      started.toString,
      java.time.Duration.between(started, Instant.now()).toMillis,
      AgentRecord.of(agent),
      serverModels,
      PromptRecord(prompt.version, prompt.sha256, prompt.messages),
      Conversation(Nil, LastRound(prompt.messages, exchange)) // one round until feedback rounds are run
    )
    stage("writing the record")
    save(record, dir.resolve("attempt.json"))
    record

  /** One round: send `sent`, write the reply's files into `domainDir`, build them, and evaluate the domain on
    * the corpus (working in `workDir`) if it compiled. Each step that cannot run is recorded as not run.
    */
  def round(sent: List[ChatMessage], client: ChatClient, tools: Tools, domainDir: Path, workDir: Path,
      stage: String => Unit): Exchange =
    stage("asking the model")
    client.chat(sent) match
      case Left(failure) => Exchange.NoReply(failure)
      case Right(reply) =>
        val parsed = Reply.files(reply.content)
        val written = write(parsed.files, domainDir)
        val build = Option.when(written.nonEmpty)(Build.run(tools.template, domainDir, tools.api, stage = stage)).map { b =>
          val (last, earlier) = (b.tries.last, b.tries.init)
          b.jar match
            case Some(jar) =>
              val tests = b.testsCompiled match
                case Some(false) => Tests.DidNotCompile(b.testLog)
                case _           => Tests.Ran(b.testsRun, b.testFailures, b.testErrors, b.testLog)
              val evaluation = Evaluate.run(tools.pag, jar, tools.corpus, tools.probeLib, workDir, stage = stage)
              BuildRecord.Compiled(last, earlier, tests,
                evaluation.fold(EvaluationRecord.Failed(_), e => EvaluationRecord.Evaluated(e.results.map(_.record))))
            case None => BuildRecord.Failed(last, earlier)
        }
        Exchange.Replied(reply, FilesRecord(written, parsed.ignoredBlocks, parsed.problems), build)

  /** The reply's files under `root`; their paths were checked safe by `Reply`. */
  private def write(files: Map[String, String], root: Path): List[String] =
    files.toList.sortBy(_._1).map { (path, text) =>
      val target = root.resolve(path).normalize
      require(target.startsWith(root), s"$path escapes $root") // Reply refuses these; a second guard costs nothing
      Files.createDirectories(target.getParent)
      Files.writeString(target, text + "\n")
      path
    }

  /** Written to a temporary file, then moved into place: a partial attempt.json never exists. */
  private def save(record: AttemptRecord, file: Path): Unit =
    import AttemptRecord.given
    val tmp = file.resolveSibling(s"${file.getFileName}.tmp")
    Files.write(tmp, Json.encode(record).withPrettyRendering(2).toByteArray)
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

  /** Just the schema; Borer skips the other keys. Records from before 2026-10-09 have none: schema 1. */
  private final case class Header(schema: Option[Int] = None)

  /** A record of any schema, in the current shape: schema 1 is converted (`AttemptRecordV1`). */
  def read(file: Path): Either[String, AttemptRecord] =
    import AttemptRecord.given
    import io.bullet.borer.Codec
    import io.bullet.borer.NullOptions.given
    import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec
    given Codec[Header] = deriveCodec[Header]
    val bytes = Files.readAllBytes(file)
    def decode[A: io.bullet.borer.Decoder]: Either[String, A] = Json.decode(bytes).to[A].valueEither.left.map(_.getMessage)
    val record = decode[Header].flatMap(_.schema match
      case None                         => decode[AttemptRecordV1.Attempt].flatMap(AttemptRecordV1.convert)
      case Some(AttemptRecord.Schema)   => decode[AttemptRecord]
      case Some(n)                      => Left(s"schema $n is not one this code reads")
    )
    record.left.map(e => s"$file: $e")
