package pag.campaign

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import javax.tools.ToolProvider
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import pag.results.{CheckResult, Incomplete, Verdict, Wire}
import pag.results.Codecs.given

/** One target's result: how `pag check` ended, and its record when it printed one. */
final case class TargetResult(
    target: Target,
    exitCode: Option[Int], // None: killed for running too long
    check: Option[CheckResult],
    stderr: String,
    timedOut: Boolean,
    elapsedMs: Long
):
  def refuted: Boolean = check.exists(_.analysis.verdict == Verdict.Refuted)

  /** Table 1's cell (experiments.md): R refuted, A alarm, ✗ refuted a reachable
    * target, I inconclusive (did not converge: iteration limit or deadline),
    * E domain failure, H hung (killed at the wall-clock bound), – anything else.
    */
  def cell: String =
    if timedOut then "H"
    else
      check.map(_.analysis.verdict) match
        case Some(Verdict.Refuted)                                 => if target.reachable then "✗" else "R"
        case Some(Verdict.Alarm)                                   => "A"
        case Some(Verdict.Inconclusive(_: Incomplete.DomainFailure)) => "E"
        case Some(Verdict.Inconclusive(_))                         => "I"
        case None                                                  => "–"

/** A domain over a corpus. */
final case class Evaluation(results: List[TargetResult]):
  /** Refutations among the targets that really are unreachable. */
  def proved: Int = results.count(r => r.refuted && !r.target.reachable)

  /** Caught unsound: a reachable target refuted. */
  def unsound: Boolean = results.exists(r => r.refuted && r.target.reachable)

/** Runs `pag check --json` for each target of a corpus against a domain jar
  * (implementation_strategy.md Phase 10), each under two clocks: pag's own
  * `--deadline`, checked between iterations, after which pag stops cleanly and
  * reports inconclusive (cell I); and a longer wall-clock kill for what pag
  * cannot stop itself, a domain stuck inside one call (cell H). The gap between
  * them keeps a slow domain from being reported as a hung one.
  */
object Evaluate:

  /** pag's deadline per target: the smoke probes take well under a second. */
  val Deadline: FiniteDuration = 20.seconds

  /** The wall-clock kill per target, comfortably after pag's own deadline. */
  val Timeout: FiniteDuration = 30.seconds

  /** `pag` is the command that runs pag, e.g. the launcher `demo_scripts/common.sh` writes. */
  def run(
      pag: List[String],
      domainJar: Path,
      corpusDir: Path,
      probeLib: Path,
      workDir: Path,
      deadline: FiniteDuration = Deadline,
      timeout: FiniteDuration = Timeout,
      stage: String => Unit = _ => ()
  ): Either[String, Evaluation] =
    require(deadline < timeout, s"pag's deadline $deadline must come before the wall-clock kill $timeout")
    for
      corpus <- Corpus.read(corpusDir)
      compiled <- compile(corpus, corpusDir, probeLib, workDir.resolve("probes"))
    yield Evaluation(corpus.targets.zipWithIndex.map { (t, i) =>
      stage(s"evaluating target ${i + 1} of ${corpus.targets.size} (${t.probe})")
      val command = pag ++ List("check", "--domain", domainJar.toString, "--classes", compiled(t.probe).toString,
        "--reach", t.reach.toString, "--inputs", t.inputs.mkString(","), "--deadline", s"${deadline.toMillis}ms",
        "--json")
      val p = Processes.run(command, timeout)
      val check = Option.when(p.exitCode.exists(Set(0, 3, 4, 5)))(p.stdout)
        .flatMap(out => Wire.decode[CheckResult](out.strip.getBytes(UTF_8)).toOption)
      TargetResult(t, p.exitCode, check, p.stderr, p.timedOut, p.elapsedMs)
    })

  /** Each probe compiled once, as probes are built (§5.5): javac -g against probe-lib alone. */
  private def compile(corpus: Corpus, from: Path, probeLib: Path, into: Path): Either[String, Map[String, Path]] =
    val javac = ToolProvider.getSystemJavaCompiler
    corpus.targets.map(_.probe).distinct.foldLeft[Either[String, Map[String, Path]]](Right(Map.empty)) { (acc, probe) =>
      acc.flatMap { done =>
        val out = Files.createDirectories(into.resolve(probe))
        val errors = java.io.ByteArrayOutputStream()
        val args = List("-g", "--release", "21", "-proc:none", "-classpath", probeLib.toString, "-d", out.toString,
          from.resolve(s"$probe.java").toString)
        if javac.run(null, null, errors, args*) == 0 then Right(done + (probe -> out))
        else Left(s"probe $probe did not compile:\n$errors")
      }
    }
