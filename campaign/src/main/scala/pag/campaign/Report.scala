package pag.campaign

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

import io.bullet.borer.{Codec, Json}
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

/** Shawn's judgment of one attempt (experiments.md, the inspection rubric). Hand
  * edited, so every field may be left out; the report only ever reads it.
  */
final case class Inspection(
    closestDomain: Option[String] = None,
    soundnessByEye: Option[String] = None,
    quality: Option[String] = None,
    acceptable: Option[Boolean] = None,
    notes: Option[String] = None
)

/** `report/inspection.json`: inspections keyed by `"<run directory>/<attempt>"`. */
final case class Inspections(attempts: Map[String, Inspection] = Map.empty)

/** A prompt as the report tells prompts apart: by its version and hash, as a run pinned or recorded it. */
final case class PromptKey(version: String, sha256: String)

/** One campaign run with one prompt, as the report reads it: the campaign's name, the run read (its directory's
  * name), the model (the name less the prefix), the prompt, when the run started, its attempts, and its pin, if
  * it has a readable one.
  */
final case class CampaignResults(name: String, run: String, model: String, prompt: PromptKey, started: Option[Instant],
    attempts: List[AttemptRecord], pin: Option[CampaignPin] = None)

/** A run directory's name, split: the campaign it runs, and its start, which directories made before
  * 2026-10-09 do not have in their names.
  */
final case class RunName(campaign: String, started: Option[Instant])

/** `campaign report` (experiments.md, The report): Tables 1 and 2 as LaTeX, and
  * the prompts for the appendix, from the attempt records and the inspection file.
  * Deterministic, so regenerating unchanged results gives identical files. Never
  * writes the inspection file.
  */
object Report:

  given Codec[Inspection] = deriveCodec[Inspection]
  given Codec[Inspections] = deriveCodec[Inspections]

  private val Timestamped = """(.+)-(\d{8}T\d{6}Z)""".r

  /** `<campaign>-<yyyyMMddTHHmmssZ>` is a run of `<campaign>`; any other name is a run from before start times. */
  def runName(dir: String): RunName = dir match
    case Timestamped(campaign, at) =>
      Try(Instant.from(Campaign.StartFormat.parse(at))).toOption.fold(RunName(dir, None))(t => RunName(campaign, Some(t)))
    case _ => RunName(dir, None)

  /** The arms (glossary, *Experiment file*): the experiment files `config/<arm>.json` by base name, sorted. */
  def arms(config: Path): List[String] =
    if !Files.isDirectory(config) then Nil
    else Using.resource(Files.list(config))(_.iterator.asScala.toList)
      .filter(f => Files.isRegularFile(f) && f.getFileName.toString.endsWith(".json"))
      .map(_.getFileName.toString.stripSuffix(".json")).sorted

  /** The arm a campaign belongs to: the longest whose name and a `-` begin the campaign's, so that
    * `e1fb82k-rung0-v4-<model>` is not put under `e1fb82k-rung0`.
    */
  def armOf(campaign: String, arms: List[String]): Option[String] =
    arms.filter(a => campaign.startsWith(s"$a-")).maxByOption(_.length)

  private def runDirs(results: Path): List[Path] =
    if !Files.isDirectory(results) then Nil
    else Using.resource(Files.list(results))(_.iterator.asScala.toList).filter(Files.isDirectory(_)).sorted

  /** The latest run of each campaign and prompt under `results` whose campaign name starts with `prefix` and
    * is `within` what is wanted, smallest model first; and warnings for the runs skipped, for a run read that
    * holds fewer attempts than its pinned sample count, and for a run that does not say which prompt it ran. A
    * run without a start in its name predates every run with one.
    */
  def latestRuns(results: Path, prefix: String, within: String => Boolean = _ => true)
      : (List[CampaignResults], List[String]) =
    val dirs = runDirs(results).map(d => (d, runName(d.getFileName.toString)))
      .filter((_, n) => n.campaign.startsWith(prefix) && within(n.campaign))
    val read = dirs.map((d, n) => (d, n, attemptsIn(d)))
    val keyed = read.map((d, n, as) => (d, n, as, promptOf(d, as)))
    val unknown = keyed.collect { case (d, _, _, None) =>
      s"${d.getFileName}: neither its campaign.json nor an attempt says which prompt it ran, so it is not read" }
    val byRun = keyed.collect { case (d, n, as, Some(p)) => ((n.campaign, p), (d, n, as)) }
      .groupMap(_._1)(_._2).toList.sortBy((k, _) => (k._1, k._2.version, k._2.sha256))
    val chosen = byRun.map { case ((campaign, prompt), runs) =>
      val ordered = runs.sortBy((d, r, _) => (r.started.isDefined, r.started.map(_.toEpochMilli).getOrElse(0L), d.toString))
      val (dir, name, attempts) = ordered.last
      val pin = Campaign.pinned(dir)
      val skipped = ordered.init.map(_._1.getFileName.toString)
      val warnings = Option.when(skipped.nonEmpty)(
          s"$campaign: read run ${dir.getFileName}, skipped ${skipped.mkString(", ")}").toList ++
        pin.flatMap(_.samples).filter(_ > attempts.size).map(n =>
          s"${dir.getFileName} holds ${attempts.size} of its $n samples: resume it before trusting its rows")
      (CampaignResults(campaign, dir.getFileName.toString, campaign.stripPrefix(prefix), prompt, name.started,
        attempts, pin), warnings)
    }
    (chosen.map(_._1).sortBy(c => (size(c.model), c.model, c.prompt.version, c.prompt.sha256)),
      unknown ++ chosen.flatMap(_._2))

  private def attemptsIn(dir: Path): List[AttemptRecord] =
    Using.resource(Files.list(dir))(_.iterator.asScala.toList)
      .map(_.resolve("attempt.json")).filter(Files.isRegularFile(_))
      .flatMap(f => Attempt.read(f).toOption).sortBy(_.attempt)

  /** The prompt a run ran: as pinned, or, for a run without a pin, as its first attempt recorded it. */
  private def promptOf(dir: Path, attempts: List[AttemptRecord]): Option[PromptKey] =
    Campaign.pinned(dir).map(p => PromptKey(p.promptVersion, p.promptSha256))
      .orElse(attempts.headOption.map(a => PromptKey(a.prompt.version, a.prompt.sha256)))

  /** Each prompt among the runs read, named by a letter for the tables and the appendix: A, B, … in order of
    * version, then hash.
    */
  def promptLetters(cs: List[CampaignResults]): Map[PromptKey, String] =
    cs.map(_.prompt).distinct.sortBy(p => (p.version, p.sha256)).zipWithIndex
      .map((p, i) => p -> (if i < 26 then ('A' + i).toChar.toString else s"P${i + 1}")).toMap

  /** How the tables name a run: its campaign and its prompt's letter. */
  def descriptor(c: CampaignResults, letters: Map[PromptKey, String]): String =
    s"${c.name} with prompt ${letters(c.prompt)}"

  /** The latest run of each campaign whose name starts with `prefix`, smallest model first. */
  def campaigns(results: Path, prefix: String): List[CampaignResults] = latestRuns(results, prefix)._1

  /** A model's size in billions of parameters, read from its name ("qwen3.5-0.8b" is 0.8); unknown sorts last. */
  def size(model: String): Double =
    "(\\d+(?:\\.\\d+)?)[bB](?![a-zA-Z])".r.findAllMatchIn(model).toList.lastOption.fold(Double.MaxValue)(_.group(1).toDouble)

  def readInspections(file: Path): Either[String, Inspections] =
    if !Files.isRegularFile(file) then Right(Inspections())
    else Json.decode(Files.readAllBytes(file)).to[Inspections].valueEither.left.map(e => s"$file: ${e.getMessage}")

  /** Whether any of these runs was pinned with build feedback: only then do the tables show rounds. */
  def withFeedback(cs: List[CampaignResults]): Boolean = cs.exists(_.pin.exists(_.feedback.isDefined))

  /** The completion tokens of every round's reply added together; blank if no reply reported them. */
  def tokens(r: AttemptRecord): String =
    (r.conversation.failed.map(_.reply) ++ r.reply).flatMap(_.completionTokens).reduceOption(_ + _).fold("")(_.toString)

  /** The round whose domain compiled, if one did: always the last, since a domain that compiles ends the
    * conversation.
    */
  def compiledIn(r: AttemptRecord): Option[Int] = r.build.collect { case _: BuildRecord.Compiled => r.rounds }

  /** Mechanically acceptable: evaluated, not caught unsound, and proves at least one target. */
  def mechanicallyAcceptable(r: AttemptRecord): Boolean =
    StatusView.outcome(r) == "evaluated" && !r.summary.unsound && r.summary.proved >= 1

  /** What `write` did: the arms given a section, in order, and its warnings. */
  final case class Written(arms: List[String], warnings: List[String])

  /** Writes each arm's tables into `out/<arm>/`, `arms.tex` with a section for each, and the corpus and the
    * prompts, shared by every arm so a prompt's letter means the same in each section. The arms are `chosen`'s,
    * or else every one of `arms` with runs; a run matching no arm is counted in one warning. Returns warnings,
    * e.g. inspections naming no attempt.
    */
  def write(results: Path, arms: List[String], chosen: Option[List[String]], inspectionFile: Path, corpus: Path,
      out: Path): Either[String, Written] =
    for
      _ <- chosen.toList.flatten.filterNot(arms.contains) match
        case Nil     => Right(())
        case unknown => Left(s"--arms names arms with no experiment file in config/: ${unknown.mkString(", ")}; " +
          s"the arms are ${arms.mkString(", ")}")
      inspections <- readInspections(inspectionFile)
      corpusTargets <- Corpus.read(corpus)
    yield
      val unmatched = runDirs(results).map(d => runName(d.getFileName.toString).campaign).filter(armOf(_, arms).isEmpty)
      val read = chosen.getOrElse(arms).distinct.map(a => (a, latestRuns(results, s"$a-", armOf(_, arms).contains(a))))
      val (withRuns, without) = read.partition(_._2._1.nonEmpty)
      val cs = withRuns.flatMap(_._2._1)
      val letters = promptLetters(cs)
      for (arm, (armRuns, _)) <- withRuns do
        val dir = Files.createDirectories(out.resolve(arm))
        Files.writeString(dir.resolve("table1.tex"), table1(armRuns, inspections, letters))
        Files.writeString(dir.resolve("table2.tex"), table2(armRuns, inspections, letters))
        Files.writeString(dir.resolve("settings.tex"), settings(armRuns, letters))
      Files.writeString(out.resolve("arms.tex"), armsTex(withRuns.map(_._1)))
      Files.writeString(out.resolve("corpus.tex"), corpusTable(corpusTargets))
      val texts = letters.toList.sortBy((p, _) => (p.version, p.sha256)).map { (p, letter) =>
        val text = cs.filter(_.prompt == p).flatMap(_.attempts).headOption
          .map(_.prompt.messages.map(m => s"=== ${m.role} ===\n${m.content}").mkString("\n\n") + "\n")
        text.foreach(t => Files.writeString(out.resolve(s"prompt-$letter.txt"), t))
        (p, letter, text.isDefined)
      }
      Files.writeString(out.resolve("prompts.tex"), promptsTex(texts))
      val unmatchedWarning = Option.when(unmatched.nonEmpty)(
        s"skipped ${if unmatched.size == 1 then "1 run" else s"${unmatched.size} runs"} matching no experiment file " +
          "in config/: " + unmatched.groupMapReduce(identity)(_ => 1)(_ + _).toList.sorted
            .map((c, n) => s"$c ($n)").mkString(", "))
      val emptyWarnings = if chosen.isEmpty then Nil
        else without.map((a, _) => s"--arms names $a, which has no runs to read in ${results.getFileName}/")
      val known = cs.flatMap(c => c.attempts.map(a => s"${c.run}/${a.attempt}")).toSet
      val reported = read.map(_._1).toSet // an inspection of an arm left out of --arms is not stray
      val stray = inspections.attempts.keySet.diff(known)
        .filter(k => armOf(runName(k.takeWhile(_ != '/')).campaign, arms).forall(reported)).toList.sorted
      Written(withRuns.map(_._1), read.flatMap(_._2._2) ++ unmatchedWarning ++ emptyWarnings ++
        stray.map(k => s"inspection.json names $k, which is not among the campaigns read"))

  /** The body of the report's results: a subsection per arm, each drawing its tables with `report.tex`'s
    * `\armtables`.
    */
  def armsTex(arms: List[String]): String =
    arms.map(a => s"\\subsection{Arm \\texttt{${escape(a)}}}\n\\armtables{$a}\n")
      .mkString("% Generated by `campaign report`; do not edit, regenerate instead.\n", "\n", "")

  /** The appendix: each prompt under its letter, with its version and hash, and its text exactly as sent. */
  def promptsTex(prompts: List[(PromptKey, String, Boolean)]): String =
    prompts.map { (p, letter, hasText) =>
      s"\\subsection*{Prompt $letter: \\texttt{${escape(p.version)}}}\nSHA-256 \\texttt{${p.sha256}}.\n\n" +
        (if hasText then s"\\VerbatimInput[fontsize=\\scriptsize,breaklines,breakanywhere]{tables/prompt-$letter.txt}\n"
         else "No attempt of these runs recorded its text.\n")
    }.mkString("% Generated by `campaign report`; do not edit, regenerate instead.\n", "\n", "")

  def table1(cs: List[CampaignResults], ins: Inspections): String = table1(cs, ins, promptLetters(cs))

  /** Table 1, naming prompts by `letters`, which may cover more runs than `cs`. */
  def table1(cs: List[CampaignResults], ins: Inspections, letters: Map[PromptKey, String]): String =
    val probes = cs.flatMap(_.attempts).flatMap(_.targets.map(_.probe)).distinct
    val feedback = withFeedback(cs)
    val header = List("Sample", "Stopped at") ++ Option.when(feedback)("Rounds") ++ List("Files", "Built", "Tests",
      "Loads") ++ probes ++ List("Proved", "Tokens", "Time", "Closest domain", "By eye", "Accept")
    val rows = cs.flatMap { c =>
      List(s"\\multicolumn{${header.size}}{l}{\\textbf{${escape(descriptor(c, letters))}}}") :: c.attempts.map { r =>
        val s = r.summary
        val i = ins.attempts.getOrElse(s"${c.run}/${r.attempt}", Inspection())
        val before = List(r.sample.toString, StatusView.outcome(r)) ++ Option.when(feedback)(r.rounds.toString) ++
          List(s.files.toString, yes(s.builds), tests(s), yes(s.loads))
        val cells = probes.map(p => r.targets.find(_.probe == p).fold("")(t => cell(t.cell)))
        val after = List(if s.loads then s.proved.toString else "",
          tokens(r), minutes(r.elapsedMs),
          i.closestDomain.getOrElse(""), i.soundnessByEye.getOrElse(""), i.acceptable.fold("")(yes))
        before.map(escape) ++ cells ++ after.map(escape)
      }
    }
    tabular("l" * header.size, header.map(escape), rows)

  def table2(cs: List[CampaignResults], ins: Inspections): String = table2(cs, ins, promptLetters(cs))

  /** Table 2, naming prompts by `letters`, which may cover more runs than `cs`. */
  def table2(cs: List[CampaignResults], ins: Inspections, letters: Map[PromptKey, String]): String =
    val stops = List("no reply", "timed out", "ran out of tokens", "no files in the reply", "did not compile", "did not load")
    val rounds = if !withFeedback(cs) then Nil
      else (1 to cs.flatMap(_.pin.flatMap(_.feedback)).map(_.rounds).max + 1).toList
    val header = List("Run", "Attempts", "Mechanical", "Inspection", "Unsound", "Median proved") ++
      rounds.map(k => s"Built in $k") ++ stops
    val rows = cs.map { c =>
      val n = c.attempts.size
      val mech = c.attempts.filter(mechanicallyAcceptable)
      val inspected = c.attempts.flatMap(a => ins.attempts.get(s"${c.run}/${a.attempt}")).flatMap(_.acceptable)
      val proved = mech.map(_.summary.proved).sorted
      val median =
        if proved.isEmpty then ""
        else if proved.size % 2 == 1 then proved(proved.size / 2).toString
        else f"${(proved(proved.size / 2 - 1) + proved(proved.size / 2)) / 2.0}%.1f"
      (List(descriptor(c, letters), n.toString, s"${mech.size}/$n", s"${inspected.count(identity)}/${inspected.size}",
        c.attempts.count(_.summary.unsound).toString, median) ++
        rounds.map(k => c.attempts.count(a => compiledIn(a).contains(k)).toString) ++
        stops.map(st => c.attempts.count(a => StatusView.outcome(a) == st).toString)).map(escape)
    }
    tabular("l" + "r" * (header.size - 1), header.map(escape), rows)

  /** The settings each campaign actually ran with, and where its model came from, read from its records. */
  def settings(cs: List[CampaignResults]): String = settings(cs, promptLetters(cs))

  /** The settings table, naming prompts by `letters`, which may cover more runs than `cs`. */
  def settings(cs: List[CampaignResults], letters: Map[PromptKey, String]): String =
    val header = List("Run", "Temp.", "top\\_p", "top\\_k", "min\\_p", "Presence", "Thinking", "Max tokens",
      "Timeout", "Build", "Feedback", "Reply rules", "llama.cpp", "Revision", "SHA-256", "Run started")
    val rows = cs.flatMap(c => c.attempts.headOption.map(r => (c, r.agent))).map { (c, a) =>
      def opt[A](o: Option[A]): String = o.fold("default")(_.toString)
      List(descriptor(c, letters), a.temperature.toString, opt(a.topP), opt(a.topK), opt(a.minP), opt(a.presencePenalty),
        a.thinking.fold("default")(t => if t then "on" else "off"), opt(a.maxTokens), s"${a.timeoutSeconds} s")
        .map(escape) ++ c.pin.fold(List.fill(4)(""))(pinned) ++
        List(a.source.revision.fold("")(_.take(12)), a.source.sha256.fold("")(_.take(12)), started(c)).map(escape)
    }
    tabular("l" * header.size, header.map(h => if h.contains("\\_") then h else escape(h)), rows)

  /** The settings columns read from a run's pin, as LaTeX: its build limits, its feedback, its reply rules and
    * its server's build, each with what a pin from before the field held.
    */
  def pinned(p: CampaignPin): List[String] =
    def duration(s: Int): String = if s % 60 == 0 then s"${s / 60} min" else s"$s s"
    val (seconds, tries) = p.build.fold((300, 1))(b => (b.timeoutSeconds, b.compileTries))
    s"${escape(duration(seconds))} $$\\times$$ $tries" :: List(
      p.feedback.fold("none")(f => s"${f.rounds} rounds, ${f.history.toString.toLowerCase}, ${f.templateVersion}"),
      p.replyRules.getOrElse("v1"), p.serverBuild.getOrElse("not recorded")).map(escape)

  /** When the run began, to the minute in UTC: from its directory's name, or its earliest attempt for an older one. */
  private def started(c: CampaignResults): String =
    c.started.orElse(c.attempts.flatMap(a => Try(Instant.parse(a.startedAt)).toOption).minOption)
      .fold("")(t => java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'").withZone(java.time.ZoneOffset.UTC).format(t))

  /** The smoke corpus: each target, its true answer, and what can prove it. */
  def corpusTable(c: Corpus): String =
    val header = List("Probe", "Rung", "Reachable", "Run with", "Provable by")
    val rows = c.targets.map(t => List(t.probe, t.rung.toString, if t.reachable then "yes" else "no",
      if t.inputs.isEmpty then "no input" else t.inputs.mkString(", "), t.provableBy).map(escape))
    tabular("lrlll", header.map(escape), rows)

  /** A tabular from cells that are already LaTeX; captions and notes live in report.tex. */
  private def tabular(spec: String, header: List[String], rows: List[List[String]]): String =
    val line = (cells: List[String]) => cells.mkString(" & ") + " \\\\"
    (List("% Generated by `campaign report`; do not edit, regenerate instead.", s"\\begin{tabular}{$spec}",
      "\\toprule", line(header), "\\midrule") ++ rows.map(line) ++ List("\\bottomrule", "\\end{tabular}"))
      .mkString("", "\n", "\n")

  /** Text with LaTeX's special characters escaped. */
  def escape(s: String): String =
    s.flatMap {
      case '\\' => "\\textbackslash{}"
      case c @ ('&' | '%' | '$' | '#' | '_' | '{' | '}') => s"\\$c"
      case '~' => "\\textasciitilde{}"
      case '^' => "\\textasciicircum{}"
      case c   => c.toString
    }

  /** A target's cell as LaTeX: the unsound mark is a math ×, the others plain letters. */
  private def cell(c: String): String = if c == "✗" then "$\\times$" else escape(c)
  private def yes(b: Boolean): String = if b then "yes" else "no"

  /** Table 1's Tests cell: passing out of run, or that the tests did not compile. Blank if the domain did not build. */
  def tests(s: Summary): String =
    if !s.builds then ""
    else if s.testsCompiled.contains(false) then "did not compile"
    else s"${s.testsRun - s.testsFailed}/${s.testsRun}"
  private def minutes(ms: Long): String = f"${ms / 60000.0}%.1f min"
