package pag.campaign

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import io.bullet.borer.Json

/** The feedback templates of one version, and a hash of exactly their text. */
final case class FeedbackTemplates(version: String, errors: String, timeout: String, sha256: String)

/** Build feedback (implementation_strategy.md §16, item 25): what goes back to
  * the model when its domain did not build. Either javac's errors, or that the
  * compiler ran out of time. The text comes from the versioned templates in
  * `campaign/prompts/<version>/`.
  */
object Feedback:

  /** The most of javac's report sent back, in UTF-8 bytes, note and count included. */
  val Cap: Int = 8192

  // An error or warning starts unindented: Gradle's repeat of the report in its failure summary is indented.
  private val Entry = """^\S.*\.java:\d+: (?:error|warning): .*""".r
  private val Count = """^(\d+) errors?$""".r

  /** javac's own report in a Gradle compile log, once, with paths relative to
    * `domainDir`, capped at `Cap` bytes between whole errors. It ends with
    * javac's count, so a cut report still says how many there were. None when
    * the log holds no complete report: the build failed some other way.
    */
  def errors(compileLog: String, domainDir: Path): Option[String] =
    val lines = compileLog.linesIterator.toList
    val start = lines.indexWhere(Entry.matches)
    val end = lines.indexWhere(Count.matches, start)
    Option.when(start >= 0 && end > start) {
      val prefix = s"$domainDir/"
      val report = lines.slice(start, end).map(_.replace(prefix, ""))
      val entries = report.indices.filter(i => Entry.matches(report(i))).toList
      val blocks = (entries :+ report.size).sliding(2).map(p => report.slice(p.head, p.last).mkString("\n")).toList
      capped(blocks, lines(end))
    }

  /** As many whole blocks as fit with the count line; if not all do, a note of
    * how many were left out. A first block that alone is too long is cut.
    */
  private def capped(blocks: List[String], count: String): String =
    def bytes(s: String): Int = s.getBytes(UTF_8).length
    def note(n: Int): String = s"… $n more not shown"
    val all = (blocks :+ count).mkString("\n")
    if bytes(all) <= Cap then all
    else
      // The room left for blocks once the note and count are in, with the longest note possible.
      val room = Cap - bytes(s"\n${note(blocks.size)}\n$count")
      val sizes = blocks.map(b => bytes(b) + 1).scanLeft(0)(_ + _).tail
      val kept = sizes.takeWhile(_ <= room).size
      val shown =
        if kept > 0 then blocks.take(kept)
        else
          // Cut by bytes, then drop the last character, which the cut may have split.
          val marker = " …"
          List(new String(blocks.head.getBytes(UTF_8).take(room - bytes(marker)), UTF_8).dropRight(1) + marker)
      (shown :+ note(blocks.size - kept.max(1)) :+ count).mkString("\n")

  /** The templates of `version`, from `campaign/prompts/<version>/`. */
  def load(repo: Path, version: String): Either[String, FeedbackTemplates] =
    val dir = repo.resolve(s"campaign/prompts/$version")
    def read(name: String): Either[String, String] =
      val file = dir.resolve(name)
      if Files.isRegularFile(file) then Right(Files.readString(file).stripTrailing)
      else Left(s"missing feedback template: $file")
    for
      errors <- read("errors.md")
      timeout <- read("timeout.md")
    yield FeedbackTemplates(version, errors, timeout, Prompt.sha256(Json.encode(List(errors, timeout)).toByteArray))

  /** The message for a build that failed with javac errors in `stage`. */
  def onErrors(t: FeedbackTemplates, stage: String, errors: String): Either[String, String] =
    Prompt.fill(t.errors, Map("stage" -> stage, "errors" -> errors))

  /** The message for a build whose every try ran out of time. */
  def onTimeout(t: FeedbackTemplates, minutes: Long, tries: Int): Either[String, String] =
    Prompt.fill(t.timeout, Map("minutes" -> minutes.toString, "tries" -> tries.toString))
