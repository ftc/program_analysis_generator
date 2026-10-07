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
    stage("asking the model")
    val chat = client.chat(prompt.messages)
    val parsed = chat.toOption.map(r => Reply.files(r.content))
    val written = parsed.map(p => write(p.files, dir.resolve("domain")))
    val build = written.filter(_.nonEmpty).map(_ => Build.run(tools.template, dir.resolve("domain"), tools.api,
      stage = stage))
    val evaluation = build.flatMap(_.jar).map { jar =>
      Evaluate.run(tools.pag, jar, tools.corpus, tools.probeLib, dir.resolve("work"), stage = stage)
    }
    val targets = evaluation.flatMap(_.toOption).fold(List.empty[TargetRecord])(_.results.map { r =>
      TargetRecord(r.target.probe, r.target.reach, r.target.reachable, r.target.rung, r.cell, r.exitCode, r.check,
        r.stderr, r.timedOut, r.elapsedMs)
    })
    val proved = evaluation.flatMap(_.toOption).fold(0)(_.proved)
    val unsound = evaluation.flatMap(_.toOption).exists(_.unsound)
    val record = AttemptRecord(
      Envelope.current(Profile),
      campaign,
      attempt,
      sample,
      started.toString,
      java.time.Duration.between(started, Instant.now()).toMillis,
      AgentRecord.of(agent),
      serverModels,
      PromptRecord(prompt.version, prompt.sha256, prompt.messages),
      chat.toOption,
      chat.left.toOption,
      parsed.map(p => FilesRecord(written.getOrElse(Nil), p.ignoredBlocks,
        p.problems ++ evaluation.flatMap(_.left.toOption).map(e => s"evaluation: $e"))),
      build.map(b => BuildRecord(b.jar.isDefined, b.compileLog, b.testsRun, b.testFailures, b.testErrors, b.testLog,
        b.elapsedMs)),
      targets,
      Summary(
        written.fold(0)(_.size),
        build.exists(_.jar.isDefined),
        build.fold(0)(_.testsRun),
        build.fold(0)(b => b.testFailures + b.testErrors),
        targets.exists(_.check.isDefined), // pag printed a result only if it loaded the domain
        targets.map(_.cell),
        proved,
        unsound
      )
    )
    stage("writing the record")
    save(record, dir.resolve("attempt.json"))
    record

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

  def read(file: Path): Either[String, AttemptRecord] =
    import AttemptRecord.given
    Json.decode(Files.readAllBytes(file)).to[AttemptRecord].valueEither.left.map(e => s"$file: ${e.getMessage}")
