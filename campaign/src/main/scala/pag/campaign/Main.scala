package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import scopt.OParser

/** The campaign driver's entry point (implementation_strategy.md §12). */
object Main:

  final case class Args(config: Option[Path] = None, campaign: Option[String] = None, samples: Int = 1,
      prompt: String = "generator-v1")

  private val parser: OParser[Unit, Args] =
    val b = OParser.builder[Args]
    import b.*
    OParser.sequence(
      programName("campaign"),
      cmd("generate")
        .text("run a campaign's samples: ask the generator, build, evaluate on the smoke corpus, record")
        .children(
          opt[Path]("config").required().valueName("<file>").action((p, a) => a.copy(config = Some(p)))
            .text("the campaign config (JSON, implementation_strategy.md §10)"),
          opt[String]("campaign").required().valueName("<name>").action((n, a) => a.copy(campaign = Some(n)))
            .text("results/<name>/; one campaign per model, e.g. e1-rung0-<model>"),
          opt[Int]("samples").valueName("N").action((n, a) => a.copy(samples = n))
            .text("how many attempts the campaign should hold (default 1); existing ones are kept"),
          opt[String]("prompt").valueName("<version>").action((v, a) => a.copy(prompt = v))
            .text("campaign/prompts/<version>/ (default generator-v1)")
        )
    )

  def main(args: Array[String]): Unit =
    OParser.parse(parser, args, Args()) match
      case None => sys.exit(1)
      case Some(a) =>
        val outcome = for
          repo <- Repo.root()
          tools <- locate(repo)
          config <- CampaignConfig.read(a.config.get)
          prompt <- Prompt.assemble(repo, a.prompt)
          agent = config.agents.generator
          done <- Campaign.generate(a.campaign.get, a.samples, agent, ChatClient(agent), prompt, tools,
            repo.resolve("results"), report)
        yield done
        outcome match
          case Left(message) => System.err.println(s"campaign: $message"); sys.exit(1)
          case Right(done)   => println(s"${done.size} samples in results/${a.campaign.get}/")

  /** One line per sample, as it finishes. */
  private def report(outcome: SampleOutcome): Unit = outcome match
    case SampleOutcome.Kept(attempt) => println(s"$attempt  kept (already recorded)")
    case SampleOutcome.Ran(r) =>
      val s = r.summary
      val what =
        if r.reply.isEmpty then s"no reply: ${r.failure.fold("?")(_.message)}"
        else if s.files == 0 then "no files in the reply"
        else if !s.builds then s"${s.files} files, did not compile"
        else s"${s.files} files, built, tests ${s.testsRun - s.testsFailed}/${s.testsRun}, " +
          s"${s.cells.mkString(" ")}, proved ${s.proved}${if s.unsound then ", UNSOUND" else ""}"
      println(f"${r.attempt}  $what  (${r.elapsedMs / 1000}%d s)")

  /** What a run needs from the build; `bash demo_scripts/common.sh` makes all of it. */
  def locate(repo: Path): Either[String, Tools] =
    val hint = "run `bash demo_scripts/common.sh` first"
    val launcher = repo.resolve("demo_scripts/out/pag")
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
