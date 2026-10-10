package pag.campaign

import java.nio.file.{Files, Path}
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.*
import scala.util.Using
import io.bullet.borer.{Codec, Json}
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

/** What a campaign holds fixed (implementation_strategy.md §12, Phase 10): if any
  * of these change, results are no longer comparable, so it is a new campaign.
  * The commit is deliberately absent — committing the attempts moves HEAD, and
  * resuming must still work — and each attempt records its own.
  */
final case class CampaignPin(
    agent: AgentRecord,
    promptVersion: String,
    promptSha256: String,
    corpusSha256: String,
    profile: String,
    samples: Option[Int] = None, // added 2026-10-09, as are the next two; None: pinned before then
    build: Option[BuildPin] = None, // None: pinned before then, when builds had 5 minutes and one try
    feedback: Option[FeedbackPin] = None // None: one shot
)

/** How domains are built (Build): the limit on each Gradle call, and how many tries a compile gets. */
final case class BuildPin(timeoutSeconds: Int, compileTries: Int)

/** Build feedback (implementation_strategy.md §16, item 25): at most `rounds` feedback rounds (at least 1; a
  * one-shot campaign pins no FeedbackPin), what each round sends, and the templates' version and hash.
  */
final case class FeedbackPin(rounds: Int, history: FeedbackHistory, templateVersion: String, templateSha256: String)

/** What a feedback round sends besides the original messages: the latest reply and its feedback, or every
  * earlier reply and feedback.
  */
enum FeedbackHistory:
  case Latest, Full

/** How `generate` starts: a new run of a campaign, or an interrupted run continued. */
enum Start:
  case Fresh(name: String, samples: Int)
  case Resume(dir: Path, samples: Option[Int]) // samples: only for a run pinned before the pin recorded it

/** What one `generate` run did for each sample. */
enum SampleOutcome:
  case Ran(record: AttemptRecord)
  case Kept(attempt: String) // attempt.json already there: never rewritten

/** Running a campaign's samples (implementation_strategy.md Phase 10, §14). Every
  * run gets a directory of its own, named after the campaign and its UTC start
  * time, so a rerun never reuses or deletes an earlier run. A run that was
  * interrupted is continued only on request (`Start.Resume`), and only under the
  * inputs it was pinned with: then only samples without an `attempt.json` run,
  * and no attempt is ever rewritten.
  */
object Campaign:

  given Codec[FeedbackHistory] = deriveCodec[FeedbackHistory]
  given Codec[BuildPin] = deriveCodec[BuildPin]
  given Codec[FeedbackPin] = deriveCodec[FeedbackPin]
  given Codec[CampaignPin] =
    import AttemptRecord.given
    deriveCodec[CampaignPin]

  /** The build settings this code runs with. */
  val CurrentBuild: BuildPin = BuildPin(Build.Timeout.toSeconds.toInt, Build.CompileTries)

  /** A run's start time in its directory name: UTC, to the second, sorting as text sorts. */
  val StartFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

  /** Runs the samples; returns the run's directory and what happened to each sample. */
  def generate(start: Start, agent: AgentConfig, client: ChatClient, prompt: Prompt, tools: Tools, results: Path,
      progress: SampleOutcome => Unit = _ => (), notice: String => Unit = _ => (), now: () => Instant = () => Instant.now(),
      feedback: Option[FeedbackSettings] = None
  ): Either[String, (Path, List[SampleOutcome])] =
    for
      corpusHash <- corpusSha256(tools.corpus)
      current = CampaignPin(AgentRecord.of(agent), prompt.version, prompt.sha256, corpusHash, Attempt.Profile,
        build = Some(CurrentBuild), feedback = feedback.map(_.pin))
      run <- start match
        case Start.Fresh(name, samples) => fresh(results, name, samples, current, now())
        case Start.Resume(dir, samples) => resume(dir, samples, current)
      (dir, samples) = run
    yield
      notice(s"campaign run $dir")
      val name = dir.getFileName.toString
      val status = StatusReporter(dir, name, samples, agent.timeoutSeconds)
      val outcomes = (1 to samples).toList.map { sample =>
        val attemptDir = dir.resolve(f"attempt-$sample%03d")
        val outcome =
          if Files.exists(attemptDir.resolve("attempt.json")) then SampleOutcome.Kept(attemptDir.getFileName.toString)
          else
            status.attempt(attemptDir.getFileName.toString)
            SampleOutcome.Ran(Attempt.run(name, sample, agent, client, prompt, tools, attemptDir, status.stage, feedback))
        progress(outcome)
        outcome
      }
      status.finished()
      (dir, outcomes)

  /** A new directory, `<name>-<start>`, pinned. Never an existing one: two starts in one second are refused. */
  private def fresh(results: Path, name: String, samples: Int, current: CampaignPin, at: Instant
  ): Either[String, (Path, Int)] =
    val dir = results.resolve(s"$name-${StartFormat.format(at)}")
    for
      // "." and ".." would name results/ itself or its parent
      _ <- Either.cond(name.matches("[A-Za-z0-9._-]+") && !name.matches("\\.+"), (),
        s"campaign name '$name': use letters, digits, '.', '_' and '-'")
      _ <- Either.cond(samples > 0, (), s"--samples must be positive, not $samples")
      _ <- Either.cond(!Files.exists(dir), (), s"$dir already exists; start again in a second")
    yield
      Files.createDirectories(dir)
      Files.write(dir.resolve("campaign.json"), Json.encode(current.copy(samples = Some(samples)))
        .withPrettyRendering(2).toByteArray)
      (dir, samples)

  /** An existing run, continued under the inputs it was pinned with, to its pinned sample count. */
  private def resume(dir: Path, samples: Option[Int], current: CampaignPin): Either[String, (Path, Int)] =
    val file = dir.resolve("campaign.json")
    for
      _ <- Either.cond(Files.isRegularFile(file), (), s"$file: no such file; --resume takes a campaign run's directory")
      pinned <- Json.decode(Files.readAllBytes(file)).to[CampaignPin].valueEither.left.map(e => s"$file: ${e.getMessage}")
      changed = List(
        Option.when(pinned.agent != current.agent)("the agent configuration"),
        Option.when(pinned.promptVersion != current.promptVersion || pinned.promptSha256 != current.promptSha256)(
          "the prompt"),
        Option.when(pinned.corpusSha256 != current.corpusSha256)("the corpus"),
        Option.when(pinned.profile != current.profile)("the profile"),
        Option.when(pinned.build != current.build)("the build settings"),
        Option.when(pinned.feedback != current.feedback)("the feedback settings")
      ).flatten
      _ <- Either.cond(changed.isEmpty, (), s"$dir was run with different inputs (${changed.mkString(", ")} changed); " +
        "start a new run instead of resuming this one")
      count <- (pinned.samples, samples) match
        case (Some(n), None)    => Right(n)
        case (None, Some(n))    => Either.cond(n > 0, n, s"--samples must be positive, not $n")
        case (Some(_), Some(_)) => Left(s"$dir pins its sample count; resume it without --samples")
        case (None, None)       => Left(s"$dir was pinned before sample counts were; resume it with --samples")
    yield (dir, count)

  /** A campaign's pinned inputs, if it has been run. */
  def pinned(dir: Path): Option[CampaignPin] =
    val file = dir.resolve("campaign.json")
    Option.when(Files.isRegularFile(file))(file)
      .flatMap(f => Json.decode(Files.readAllBytes(f)).to[CampaignPin].valueEither.toOption)

  /** A hash of the corpus: its manifest and every probe source, in name order. */
  def corpusSha256(corpus: Path): Either[String, String] =
    Corpus.read(corpus).map { c =>
      val files = "manifest.json" :: c.targets.map(t => s"${t.probe}.java").distinct.sorted
      val bytes = files.flatMap(f => f.getBytes("UTF-8").toList ++ Files.readAllBytes(corpus.resolve(f)).toList)
      Prompt.sha256(bytes.toArray)
    }
