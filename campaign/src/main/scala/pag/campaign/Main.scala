package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import scopt.OParser

/** The campaign driver's entry point (implementation_strategy.md §12). */
object Main:

  final case class Args(command: String = "", config: Option[Path] = None, campaign: Option[String] = None,
      resume: Option[Path] = None, samples: Option[Int] = None, prompt: String = "generator-v2", every: Option[Int] = None, prefix: String = "e1-rung0-",
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
          opt[Path]("config").required().valueName("<file>").action((p, a) => a.copy(config = Some(p)))
            .text("the campaign config (JSON, implementation_strategy.md §10)"),
          opt[String]("campaign").valueName("<name>").action((n, a) => a.copy(campaign = Some(n)))
            .text("start a new run in results/<name>-<UTC start>/, e.g. e1-rung0-<model>; nothing earlier is touched"),
          opt[Path]("resume").valueName("<dir>").action((d, a) => a.copy(resume = Some(d)))
            .text("continue an interrupted run, results/<name>-<start>/, under the inputs it was pinned with"),
          opt[Int]("samples").valueName("N").action((n, a) => a.copy(samples = Some(n)))
            .text("how many attempts a new run holds (default 1); a resumed run keeps its pinned count"),
          opt[String]("prompt").valueName("<version>").action((v, a) => a.copy(prompt = v))
            .text("campaign/prompts/<version>/ (default generator-v2)")
        ),
      cmd("status")
        .action((_, a) => a.copy(command = "status"))
        .text("show a campaign's progress, its finished attempts, and warnings")
        .children(
          opt[String]("campaign").required().valueName("<name>").action((n, a) => a.copy(campaign = Some(n))),
          opt[Int]("every").valueName("SECONDS").action((n, a) => a.copy(every = Some(n)))
            .text("refresh every SECONDS until interrupted (Ctrl-C)")
        ),
      cmd("report")
        .action((_, a) => a.copy(command = "report"))
        .text("write Tables 1 and 2 and the prompt into report/tables/ from the campaigns' records")
        .children(
          opt[String]("prefix").valueName("<prefix>").action((p, a) => a.copy(prefix = p))
            .text("the campaigns to include: names starting with this (default e1-rung0-)"),
          opt[Path]("out").valueName("<dir>").action((p, a) => a.copy(out = Some(p)))
            .text("where the tables go (default report/tables)")
        ),
      checkConfig(a =>
        if a.command.isEmpty then failure("no command given")
        else if a.command == "generate" && a.campaign.isDefined == a.resume.isDefined then
          failure("generate takes exactly one of --campaign (a new run) and --resume (an interrupted one)")
        else success)
    )

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
            println(s"wrote table1.tex, table2.tex, settings.tex, corpus.tex and prompt.txt in $out")
      case Some(a) if a.command == "status" =>
        Repo.root() match
          case Left(e)     => System.err.println(s"campaign: $e"); sys.exit(1)
          case Right(repo) => watch(repo.resolve("results").resolve(a.campaign.get), a.campaign.get, a.every)
      case Some(a) =>
        val outcome = for
          repo <- Repo.root()
          tools <- locate(repo)
          config <- CampaignConfig.read(a.config.get)
          prompt <- Prompt.assemble(repo, a.prompt)
          agent = config.agents.generator
          start = a.resume.fold(Start.Fresh(a.campaign.get, a.samples.getOrElse(1)))(Start.Resume(_, a.samples))
          done <- Campaign.generate(start, agent, ChatClient(agent), prompt, tools, repo.resolve("results"), report,
            println)
        yield done
        outcome match
          case Left(message)         => System.err.println(s"campaign: $message"); sys.exit(1)
          case Right((dir, samples)) => println(s"${samples.size} samples in $dir/")

  /** Draws the status screen once, or every `every` seconds until interrupted. */
  private def watch(dir: Path, name: String, every: Option[Int]): Unit =
    val baseUrl = Campaign.pinned(dir).map(_.agent.baseUrl)
    @scala.annotation.tailrec
    def loop(history: List[Reading]): Unit =
      val now = java.time.Instant.now()
      val slots = baseUrl.toRight("model     no campaign.json yet: unknown server").flatMap(Slots.fetch)
      val reading = slots.toOption.flatMap(_.filter(_.busy).maxByOption(_.decoded)).map(s => Reading(now, s.decoded))
      val kept = (history ++ reading).filter(r => java.time.Duration.between(r.at, now).getSeconds <= 300)
      val attempts = attemptRecords(dir)
      val screen = StatusView.render(now, name, Status.read(dir), attempts, slots, kept)
      if every.isDefined then print("\u001b[H\u001b[2J") // clear the terminal
      println(screen.mkString("\n"))
      every match
        case Some(s) => Thread.sleep(s * 1000L); loop(kept)
        case None    => ()
    loop(Nil)

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
