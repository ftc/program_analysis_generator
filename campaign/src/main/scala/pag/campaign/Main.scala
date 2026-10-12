package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import scopt.OParser

/** The campaign driver's entry point (implementation_strategy.md §12). */
object Main:

  final case class Args(command: String = "", experiment: Option[Path] = None, model: Option[Path] = None,
      campaign: Option[String] = None, resume: Option[Path] = None, every: Option[Int] = None, prefix: String = "e1-rung0-",
      out: Option[Path] = None)

  private val parser: OParser[Unit, Args] =
    val b = OParser.builder[Args]
    import b.*
    OParser.sequence(
      programName("campaign"),
      cmd("generate")
        .action((_, a) => a.copy(command = "generate"))
        .text("run a campaign's samples: ask the generator, build, evaluate on the smoke corpus, record")
        .children(
          opt[Path]("experiment").required().valueName("<file>").action((p, a) => a.copy(experiment = Some(p)))
            .text("the experiment file, config/<arm>.json: every setting of the run (implementation_strategy.md §10)"),
          opt[Path]("model").required().valueName("<file>").action((p, a) => a.copy(model = Some(p)))
            .text("the provenance record, config/models/<gguf>.json, written by scripts/serve_model.sh"),
          opt[Path]("resume").valueName("<dir>").action((d, a) => a.copy(resume = Some(d)))
            .text("continue an interrupted run of the same files, results/<arm>-<model>-<start>/, under its pin")
        ),
      cmd("status")
        .action((_, a) => a.copy(command = "status"))
        .text("show the most recent campaign run's progress, its finished attempts, and warnings")
        .children(
          opt[String]("campaign").valueName("<dir>").action((n, a) => a.copy(campaign = Some(n)))
            .text("show this run, results/<dir>/, instead of the most recent one"),
          opt[Int]("every").valueName("SECONDS").action((n, a) => a.copy(every = Some(n)))
            .text("refresh every SECONDS until interrupted (Ctrl-C)")
        ),
      cmd("report")
        .action((_, a) => a.copy(command = "report"))
        .text("write Tables 1 and 2 and the prompts into report/tables/ from the campaigns' records")
        .children(
          opt[String]("prefix").valueName("<prefix>").action((p, a) => a.copy(prefix = p))
            .text("the campaigns to include: names starting with this (default e1-rung0-)"),
          opt[Path]("out").valueName("<dir>").action((p, a) => a.copy(out = Some(p)))
            .text("where the tables go (default report/tables)")
        ),
      checkConfig(a =>
        if a.command.isEmpty then failure("no command given")
        else success)
    )

  /** The build feedback templates a campaign with feedback rounds uses. */
  val FeedbackTemplateVersion: String = "feedback-build-v1"

  def main(args: Array[String]): Unit =
    OParser.parse(parser, args, Args()) match
      case None => sys.exit(1)
      case Some(a) if a.command == "report" =>
        val outcome = Repo.root().flatMap { repo =>
          val out = a.out.getOrElse(repo.resolve("report/tables"))
          Report.write(repo.resolve("results"), a.prefix, repo.resolve("report/inspection.json"),
            repo.resolve("corpora/smoke"), out).map(w => (out, w))
        }
        outcome match
          case Left(e) => System.err.println(s"campaign: $e"); sys.exit(1)
          case Right((out, warnings)) =>
            warnings.foreach(w => System.err.println(s"campaign: warning: $w"))
            println(s"wrote table1.tex, table2.tex, settings.tex, corpus.tex, prompts.tex and prompt-<letter>.txt in $out")
      case Some(a) if a.command == "status" =>
        Repo.root() match
          case Left(e)     => System.err.println(s"campaign: $e"); sys.exit(1)
          case Right(repo) => watch(repo.resolve("results"), a.campaign, a.every)
      case Some(a) =>
        val outcome = for
          repo <- Repo.root()
          tools <- locate(repo)
          experiment <- Experiment.read(a.experiment.get, a.model.get)
          prompt <- Prompt.assemble(repo, experiment.prompt)
          agent = experiment.agent
          feedback <- Option.when(experiment.feedbackRounds != 0)(experiment.feedbackRounds).fold(Right(None))(n =>
            Feedback.load(repo, FeedbackTemplateVersion).flatMap(FeedbackSettings.of(n, _)).map(Some(_)))
          start <- experiment.start(a.resume)
          done <- Campaign.generate(start, agent, ChatClient(agent), prompt, tools, repo.resolve("results"), report,
            println, feedback = feedback)
        yield done
        outcome match
          case Left(message)         => System.err.println(s"campaign: $message"); sys.exit(1)
          case Right((dir, samples)) => println(s"${samples.size} samples in $dir/")

  /** Draws the status screen once, or every `every` seconds until interrupted. Unless a run is named, the
    * most recent one is chosen again at every refresh, so the screen follows the next run when it starts.
    */
  private def watch(results: Path, named: Option[String], every: Option[Int]): Unit =
    @scala.annotation.tailrec
    def loop(shown: Option[Path], history: List[Reading]): Unit =
      val now = java.time.Instant.now()
      val chosen = named match
        case Some(n) => Some((results.resolve(n), "", Nil))
        case None    => Status.latest(results).map(c => (c.dir, "most recent run", c.othersRunning))
      val (screen, next, kept) = chosen match
        case None => (List(s"no campaign has run yet in $results"), None, Nil)
        case Some((dir, how, others)) =>
          val earlier = if shown.contains(dir) then history else Nil // a new run: its tokens start afresh
          val slots = Campaign.pinned(dir).map(_.agent.baseUrl)
            .toRight("model     no campaign.json yet: unknown server").flatMap(Slots.fetch)
          val reading = slots.toOption.flatMap(_.filter(_.busy).maxByOption(_.decoded)).map(s => Reading(now, s.decoded))
          val kept = (earlier ++ reading).filter(r => java.time.Duration.between(r.at, now).getSeconds <= 300)
          (StatusView.render(now, dir.getFileName.toString, Status.read(dir), attemptRecords(dir), slots, kept, how,
            others), Some(dir), kept)
      if every.isDefined then print("\u001b[H\u001b[2J") // clear the terminal
      println(screen.mkString("\n"))
      every match
        case Some(s) => Thread.sleep(s * 1000L); loop(next, kept)
        case None    => ()
    loop(None, Nil)

  private def attemptRecords(dir: Path): List[AttemptRecord] =
    if !Files.isDirectory(dir) then Nil
    else Using.resource(Files.list(dir))(_.iterator.asScala.toList)
      .map(_.resolve("attempt.json")).filter(Files.isRegularFile(_))
      .flatMap(f => Attempt.read(f).toOption).sortBy(_.attempt)

  /** One line per sample, as it finishes. */
  private def report(outcome: SampleOutcome): Unit = outcome match
    case SampleOutcome.Kept(attempt) => println(s"$attempt  kept (already recorded)")
    case SampleOutcome.Ran(r) =>
      val s = r.summary
      val what =
        if r.reply.isEmpty then s"no reply: ${r.failure.fold("?")(_.message)}"
        else if StatusView.outcome(r) == "ran out of tokens" then
          s"ran out of tokens at ${r.agent.maxTokens.fold("the server's limit")(_.toString)}"
        else if s.files == 0 then "no files in the reply"
        else if !s.builds then s"${s.files} files, did not compile"
        else s"${s.files} files, built, tests ${s.testsRun - s.testsFailed}/${s.testsRun}, " +
          s"${s.cells.mkString(" ")}, proved ${s.proved}${if s.unsound then ", UNSOUND" else ""}"
      println(f"${r.attempt}  $what  (${r.elapsedMs / 1000}%d s)")

  /** What a run needs from the build; `bash scripts/common.sh` makes all of it. */
  def locate(repo: Path): Either[String, Tools] =
    val hint = "run `bash scripts/common.sh` first"
    val launcher = repo.resolve("scripts/out/pag")
    val probeLib = repo.resolve("engine/probe-lib/target/classes")
    val api = Option(repo.resolve("engine/api/target")).filter(Files.isDirectory(_)).flatMap { dir =>
      Using.resource(Files.list(dir))(_.iterator.asScala.find(_.getFileName.toString.matches("pag-api-.*\\.jar")))
    }
    for
      _ <- Either.cond(Files.isExecutable(launcher), (), s"no pag launcher at $launcher; $hint")
      _ <- Either.cond(Files.isDirectory(probeLib.resolve("pag/probe")), (), s"probe-lib not compiled at $probeLib; $hint")
      apiJar <- api.toRight(s"no api jar in ${repo.resolve("engine/api/target")}; $hint")
    yield Tools(List(launcher.toString), repo.resolve("domains/build-template"), apiJar, probeLib,
      repo.resolve("corpora/smoke"))
