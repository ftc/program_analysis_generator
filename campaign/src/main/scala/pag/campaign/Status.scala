package pag.campaign

import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant

import io.bullet.borer.{Codec, Json}
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

/** What `generate` is doing right now, rewritten at each stage as
  * `results/<campaign>/status.json` for `campaign status` to read.
  */
final case class Status(
    campaign: String,
    samplesRequested: Int,
    attempt: Option[String], // None between attempts, or when the run is over
    stage: String,
    stageStartedAt: String, // ISO-8601
    attemptStartedAt: Option[String],
    timeoutSeconds: Int, // the client's, so the screen can count down to it
    updatedAt: String,
    running: Boolean // false once generate has finished
)

object Status:
  given Codec[Status] = deriveCodec[Status]

  def write(dir: Path, status: Status): Unit =
    Files.createDirectories(dir)
    val file = dir.resolve("status.json")
    val tmp = dir.resolve("status.json.tmp")
    Files.write(tmp, Json.encode(status).withPrettyRendering(2).toByteArray)
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

  def read(dir: Path): Option[Status] =
    val file = dir.resolve("status.json")
    Option.when(Files.isRegularFile(file))(file)
      .flatMap(f => Json.decode(Files.readAllBytes(f)).to[Status].valueEither.toOption)

/** Reports stage changes for one campaign run: the status file and, for a person
  * watching the run itself, nothing more. Stages are short phrases.
  */
final class StatusReporter(dir: Path, campaign: String, samples: Int, timeoutSeconds: Int):
  private var current: Status = // the reporter's own state, rewritten at each stage
    val now = Instant.now().toString
    Status(campaign, samples, None, "starting", now, None, timeoutSeconds, now, running = true)

  def attempt(id: String): Unit =
    val now = Instant.now().toString
    update(current.copy(attempt = Some(id), stage = "starting", stageStartedAt = now, attemptStartedAt = Some(now)))

  def stage(name: String): Unit = update(current.copy(stage = name, stageStartedAt = Instant.now().toString))

  def finished(): Unit =
    update(current.copy(attempt = None, stage = "finished", stageStartedAt = Instant.now().toString,
      attemptStartedAt = None, running = false))

  private def update(next: Status): Unit =
    current = next.copy(updatedAt = Instant.now().toString)
    Status.write(dir, current)
