package pag.campaign

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.Using

import io.bullet.borer.Json
import pag.campaign.ChatClient.given

/** The messages sent to the generator, which version of the templates made
  * them, and a hash of exactly what was sent (experiments.md E1).
  */
final case class Prompt(version: String, messages: List[ChatMessage], sha256: String)

/** Assembles the generator prompt (implementation_strategy.md Phase 10) from the
  * templates in `campaign/prompts/<version>/` and files in the repository. One
  * slot so far, `{{contract}}`: `engine/api`'s sources, what a domain compiles
  * against. The prompt starts with the least information and grows only when
  * models fail (experiments.md E1, the information ladder); each rung added
  * brings its slot. A slot the templates use that is not known is an error: a
  * prompt is never sent half-assembled.
  */
object Prompt:

  def assemble(repo: Path, version: String): Either[String, Prompt] =
    val templates = repo.resolve(s"campaign/prompts/$version")
    for
      system <- read(templates.resolve("system.md"))
      user <- read(templates.resolve("user.md"))
      slots = Map(
        "contract" -> fenced(repo.resolve("engine/api/src/main/java"), repo.resolve("engine/api/src/main/java/pag/api"))
      )
      filledSystem <- fill(system, slots)
      filledUser <- fill(user, slots)
    yield
      val messages = List(ChatMessage("system", filledSystem), ChatMessage("user", filledUser))
      Prompt(version, messages, sha256(Json.encode(messages).toByteArray))

  /** `text` with every `{{slot}}` replaced; an unknown slot, or a known one left over, is an error. */
  def fill(text: String, slots: Map[String, String]): Either[String, String] =
    val used = "\\{\\{(\\w+)\\}\\}".r.findAllMatchIn(text).map(_.group(1)).toSet
    val unknown = used -- slots.keySet
    if unknown.nonEmpty then Left(s"unknown prompt slots: ${unknown.toList.sorted.mkString(", ")}")
    else Right(slots.foldLeft(text) { case (t, (name, value)) => t.replace(s"{{$name}}", value) })

  /** Every .java file under `dir`, sorted, each fenced with its path relative to `base`. */
  private def fenced(base: Path, dir: Path): String =
    Using.resource(Files.walk(dir)) { files =>
      files.iterator.asScala.filter(_.toString.endsWith(".java")).toList.sortBy(_.toString).map { f =>
        s"```java ${base.relativize(f)}\n${Files.readString(f).stripTrailing}\n```"
      }.mkString("\n\n")
    }

  private def read(file: Path): Either[String, String] =
    if Files.isRegularFile(file) then Right(Files.readString(file)) else Left(s"missing prompt input: $file")

  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
