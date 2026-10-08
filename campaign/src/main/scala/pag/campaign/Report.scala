package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

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

/** `report/inspection.json`: inspections keyed by `"<campaign>/<attempt>"`. */
final case class Inspections(attempts: Map[String, Inspection] = Map.empty)

/** One model's campaign, as the report reads it. */
final case class CampaignResults(name: String, model: String, attempts: List[AttemptRecord])

/** `campaign report` (experiments.md, The report): Tables 1 and 2 as LaTeX, and
  * the prompt for the appendix, from the attempt records and the inspection file.
  * Deterministic, so regenerating unchanged results gives identical files. Never
  * writes the inspection file.
  */
object Report:

  given Codec[Inspection] = deriveCodec[Inspection]
  given Codec[Inspections] = deriveCodec[Inspections]

  /** The campaigns under `results` whose names start with `prefix`, smallest model first. */
  def campaigns(results: Path, prefix: String): List[CampaignResults] =
    if !Files.isDirectory(results) then Nil
    else
      Using.resource(Files.list(results))(_.iterator.asScala.toList)
        .filter(d => Files.isDirectory(d) && d.getFileName.toString.startsWith(prefix))
        .map { d =>
          val name = d.getFileName.toString
          val attempts = Using.resource(Files.list(d))(_.iterator.asScala.toList)
            .map(_.resolve("attempt.json")).filter(Files.isRegularFile(_))
            .flatMap(f => Attempt.read(f).toOption).sortBy(_.attempt)
          CampaignResults(name, name.stripPrefix(prefix), attempts)
        }
        .sortBy(c => (size(c.model), c.model))

  /** A model's size in billions of parameters, read from its name ("qwen3.5-0.8b" is 0.8); unknown sorts last. */
  def size(model: String): Double =
    "(\\d+(?:\\.\\d+)?)[bB](?![a-zA-Z])".r.findAllMatchIn(model).toList.lastOption.fold(Double.MaxValue)(_.group(1).toDouble)

  def readInspections(file: Path): Either[String, Inspections] =
    if !Files.isRegularFile(file) then Right(Inspections())
    else Json.decode(Files.readAllBytes(file)).to[Inspections].valueEither.left.map(e => s"$file: ${e.getMessage}")

  /** Mechanically acceptable: evaluated, not caught unsound, and proves at least one target. */
  def mechanicallyAcceptable(r: AttemptRecord): Boolean =
    StatusView.outcome(r) == "evaluated" && !r.summary.unsound && r.summary.proved >= 1

  /** Writes the tables and the prompt; returns warnings, e.g. inspections naming no attempt. */
  def write(results: Path, prefix: String, inspectionFile: Path, corpus: Path, out: Path): Either[String, List[String]] =
    for
      inspections <- readInspections(inspectionFile)
      corpusTargets <- Corpus.read(corpus)
    yield
      val cs = campaigns(results, prefix)
      Files.createDirectories(out)
      Files.writeString(out.resolve("table1.tex"), table1(cs, inspections))
      Files.writeString(out.resolve("table2.tex"), table2(cs, inspections))
      Files.writeString(out.resolve("settings.tex"), settings(cs))
      Files.writeString(out.resolve("corpus.tex"), corpusTable(corpusTargets))
      cs.flatMap(_.attempts).headOption.foreach { r =>
        Files.writeString(out.resolve("prompt.txt"),
          r.prompt.messages.map(m => s"=== ${m.role} ===\n${m.content}").mkString("\n\n") + "\n")
      }
      val known = cs.flatMap(c => c.attempts.map(a => s"${c.name}/${a.attempt}")).toSet
      val stray = inspections.attempts.keySet.diff(known).toList.sorted
      val prompts = cs.flatMap(_.attempts).map(_.prompt.sha256).distinct
      stray.map(k => s"inspection.json names $k, which is not among the campaigns read") ++
        Option.when(prompts.size > 1)(s"the campaigns used ${prompts.size} different prompts; prompt.txt shows the first")

  def table1(cs: List[CampaignResults], ins: Inspections): String =
    val probes = cs.flatMap(_.attempts).flatMap(_.targets.map(_.probe)).distinct
    val header = List("Model", "Sample", "Stopped at", "Files", "Built", "Tests", "Loads") ++
      probes ++ List("Proved", "Tokens", "Time", "Closest domain", "By eye", "Accept")
    val rows = cs.flatMap { c =>
      c.attempts.map { r =>
        val s = r.summary
        val i = ins.attempts.getOrElse(s"${c.name}/${r.attempt}", Inspection())
        val before = List(c.model, r.sample.toString, StatusView.outcome(r), s.files.toString, yes(s.builds),
          if s.builds then s"${s.testsRun - s.testsFailed}/${s.testsRun}" else "", yes(s.loads))
        val cells = probes.map(p => r.targets.find(_.probe == p).fold("")(t => cell(t.cell)))
        val after = List(if s.loads then s.proved.toString else "",
          r.reply.flatMap(_.completionTokens).fold("")(_.toString), minutes(r.elapsedMs),
          i.closestDomain.getOrElse(""), i.soundnessByEye.getOrElse(""), i.acceptable.fold("")(yes))
        before.map(escape) ++ cells ++ after.map(escape)
      }
    }
    tabular("l" * header.size, header.map(escape), rows)

  def table2(cs: List[CampaignResults], ins: Inspections): String =
    val stops = List("no reply", "timed out", "ran out of tokens", "no files in the reply", "did not compile", "did not load")
    val header = List("Model", "Attempts", "Mechanical", "Inspection", "Unsound", "Median proved") ++ stops
    val rows = cs.map { c =>
      val n = c.attempts.size
      val mech = c.attempts.filter(mechanicallyAcceptable)
      val inspected = c.attempts.flatMap(a => ins.attempts.get(s"${c.name}/${a.attempt}")).flatMap(_.acceptable)
      val proved = mech.map(_.summary.proved).sorted
      val median =
        if proved.isEmpty then ""
        else if proved.size % 2 == 1 then proved(proved.size / 2).toString
        else f"${(proved(proved.size / 2 - 1) + proved(proved.size / 2)) / 2.0}%.1f"
      (List(c.model, n.toString, s"${mech.size}/$n", s"${inspected.count(identity)}/${inspected.size}",
        c.attempts.count(_.summary.unsound).toString, median) ++
        stops.map(st => c.attempts.count(a => StatusView.outcome(a) == st).toString)).map(escape)
    }
    tabular("l" + "r" * (header.size - 1), header.map(escape), rows)

  /** The settings each campaign actually ran with, and where its model came from, read from its records. */
  def settings(cs: List[CampaignResults]): String =
    val header = List("Model", "Temp.", "top\\_p", "top\\_k", "min\\_p", "Presence", "Thinking", "Max tokens",
      "Timeout", "Revision", "SHA-256")
    val rows = cs.flatMap(c => c.attempts.headOption.map(r => (c, r.agent))).map { (c, a) =>
      def opt[A](o: Option[A]): String = o.fold("default")(_.toString)
      List(c.model, a.temperature.toString, opt(a.topP), opt(a.topK), opt(a.minP), opt(a.presencePenalty),
        a.thinking.fold("default")(t => if t then "on" else "off"), opt(a.maxTokens), s"${a.timeoutSeconds} s",
        a.source.revision.fold("")(_.take(12)), a.source.sha256.fold("")(_.take(12))).map(escape)
    }
    tabular("l" * header.size, header.map(h => if h.contains("\\_") then h else escape(h)), rows)

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
  private def minutes(ms: Long): String = f"${ms / 60000.0}%.1f min"
