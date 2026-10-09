package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import io.bullet.borer.{Codec, Json}
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
    profile: String
)

/** What one `generate` run did for each sample. */
enum SampleOutcome:
  case Ran(record: AttemptRecord)
  case Kept(attempt: String) // attempt.json already there: never rewritten

/** Running a campaign's samples (implementation_strategy.md Phase 10): pin its
  * inputs on the first run; under the same inputs, run only the samples that have
  * no `attempt.json` and never rewrite one; under different inputs, delete the
  * campaign's directory and start afresh, so two experiments' samples never mix.
  * The deleted attempts live on in git history once committed.
  */
object Campaign:

  given Codec[CampaignPin] =
    import AttemptRecord.given
    deriveCodec[CampaignPin]

  def generate(name: String, samples: Int, agent: AgentConfig, client: ChatClient, prompt: Prompt, tools: Tools,
      results: Path, progress: SampleOutcome => Unit = _ => (), notice: String => Unit = _ => ()
  ): Either[String, List[SampleOutcome]] =
    val dir = results.resolve(name)
    for
      // "." and ".." would name results/ itself or its parent, which a changed input then deletes
      _ <- Either.cond(name.matches("[A-Za-z0-9._-]+") && !name.matches("\\.+"), (),
        s"campaign name '$name': use letters, digits, '.', '_' and '-'")
      _ <- Either.cond(samples > 0, (), s"--samples must be positive, not $samples")
      corpusHash <- corpusSha256(tools.corpus)
      _ <- pin(dir, CampaignPin(AgentRecord.of(agent), prompt.version, prompt.sha256, corpusHash, Attempt.Profile), notice)
    yield
      val status = StatusReporter(dir, name, samples, agent.timeoutSeconds)
      val outcomes = (1 to samples).toList.map { sample =>
        val attemptDir = dir.resolve(f"attempt-$sample%03d")
        val outcome =
          if Files.exists(attemptDir.resolve("attempt.json")) then SampleOutcome.Kept(attemptDir.getFileName.toString)
          else
            status.attempt(attemptDir.getFileName.toString)
            SampleOutcome.Ran(Attempt.run(name, sample, agent, client, prompt, tools, attemptDir, status.stage))
        progress(outcome)
        outcome
      }
      status.finished()
      outcomes

  /** Writes the pin on a campaign's first run. Afterwards, the same pin keeps
    * the directory; a different one deletes it, says what changed, and pins afresh.
    * An unreadable pin is an error: deleting what cannot be read is a guess.
    */
  private def pin(dir: Path, current: CampaignPin, notice: String => Unit): Either[String, Unit] =
    val file = dir.resolve("campaign.json")
    def write(): Unit =
      Files.createDirectories(dir)
      Files.write(file, Json.encode(current).withPrettyRendering(2).toByteArray)
    if !Files.exists(file) then Right(write())
    else
      Json.decode(Files.readAllBytes(file)).to[CampaignPin].valueEither.left.map(e => s"$file: ${e.getMessage}")
        .flatMap { pinned =>
          val changed = List(
            Option.when(pinned.agent != current.agent)("the agent configuration"),
            Option.when(pinned.promptVersion != current.promptVersion || pinned.promptSha256 != current.promptSha256)(
              "the prompt"),
            Option.when(pinned.corpusSha256 != current.corpusSha256)("the corpus"),
            Option.when(pinned.profile != current.profile)("the profile")
          ).flatten
          if changed.nonEmpty then
            notice(s"campaign ${dir.getFileName} was run with different inputs (${changed.mkString(", ")} changed); " +
              s"deleting $dir and starting afresh")
            deleteTree(dir)
            write()
          Right(())
        }

  /** Deletes a directory and everything under it; symbolic links are deleted, not followed. */
  private def deleteTree(dir: Path): Unit =
    Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

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
