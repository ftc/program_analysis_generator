package pag.cli

import java.io.PrintStream
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

import scala.concurrent.duration.Duration

import pag.core.{Analysis, AnalysisResult, Incomplete, Limits, Lifting, Lowering, Profile, ProfileCheck, Query,
  Reachable, Verdict}
import pag.harness.{Check, Ended, IrInterpreter, JvmRun, Outcome}
import pag.ir.{IrProvider, Loc, Program, Untranslatable}
import scopt.{OEffect, OParser}

/** The `pag` entry point (implementation_strategy.md §11). Exit codes: 0 done,
  * 1 usage or input error, 2 did not load (untranslatable or profile violation),
  * 4 inconclusive (a limit, or the engine itself out of memory or stack), 5 domain
  * failure.
  */
object Main:

  /** What the command line asked for. */
  final case class Config(
      command: Option[String] = None,
      classes: Option[Path] = None,
      cfg: Boolean = false,
      lift: Boolean = true,
      enforce: Boolean = true,
      inputs: String = "",
      trace: Boolean = false,
      stepLimit: Int = IrInterpreter.DefaultStepLimit,
      domain: Option[Path] = None,
      reach: Option[Int] = None,
      limits: Limits = Limits(),
      all: Boolean = false
  )

  private val parser: OParser[Unit, Config] =
    val b = OParser.builder[Config]
    import b.*
    // defs, not vals: each command gets its own option instances
    def inputsOption = opt[String]("inputs").valueName("3,-7").action((s, c) => c.copy(inputs = s))
      .text("the values randInt returns, in order (§5.6)")
    def analysisOptions = List(
      opt[Path]("domain").required().valueName("<jar>").action((p, c) => c.copy(domain = Some(p)))
        .text("the domain jar: exactly one class implementing pag.api.Domain"),
      opt[Path]("classes").required().valueName("<dir>").action((p, c) => c.copy(classes = Some(p)))
        .text("a directory holding the program's one class"),
      opt[Int]("reach").required().valueName("ID").action((n, c) => c.copy(reach = Some(n)))
        .text("the query: is reach(ID) reachable?"),
      opt[Int]("iteration-limit").valueName("N")
        .validate(n => if n > 0 then success else failure("--iteration-limit must be positive"))
        .action((n, c) => c.copy(limits = c.limits.copy(iterations = n)))
        .text(s"stop after N transitions (default ${Limits().iterations})"),
      opt[Duration]("deadline").valueName("60s")
        .validate(d => if d.isFinite && d.toMillis > 0 then success else failure("--deadline must be positive"))
        .action((d, c) => c.copy(limits = c.limits.copy(deadlineMs = d.toMillis)))
        .text(s"stop after this long (default ${Limits().deadlineMs / 1000}s)")
    )
    OParser.sequence(
      programName("pag"),
      cmd("ir")
        .action((_, c) => c.copy(command = Some("ir")))
        .text("print what the front end produced for a directory holding one class")
        .children(
          arg[Path]("<classes-dir>").required().action((p, c) => c.copy(classes = Some(p))),
          opt[Unit]("cfg").action((_, c) => c.copy(cfg = true)).text("also lower and print the CFG"),
          opt[Unit]("no-lift").action((_, c) => c.copy(lift = false)).text("show the IR before lifting (§5.7)"),
          opt[Unit]("no-enforce").action((_, c) => c.copy(enforce = false))
            .text("skip the profile check, for inspection only (§5.2)")
        ),
      cmd("run")
        .action((_, c) => c.copy(command = Some("run")))
        .text("run a directory's one class on the IR interpreter, reporting the reach ids it passes")
        .children(
          arg[Path]("<classes-dir>").required().action((p, c) => c.copy(classes = Some(p))),
          inputsOption,
          opt[Unit]("trace").action((_, c) => c.copy(trace = true)).text("also print every location visited"),
          opt[Int]("step-limit").action((n, c) => c.copy(stepLimit = n))
            .text(s"stop after this many transitions (default ${IrInterpreter.DefaultStepLimit})")
        ),
      cmd("analyze")
        .action((_, c) => c.copy(command = Some("analyze")))
        .text("analyze a reach query with a domain, printing the verdict and the invariant map")
        .children((analysisOptions :+ opt[Unit]("all").action((_, c) => c.copy(all = true))
          .text("print every location, post locations and nops included"))*),
      cmd("check")
        .action((_, c) => c.copy(command = Some("check")))
        .text("analyze, then run the program on the JVM with the given inputs; exit 3 if a refutation is contradicted")
        .children((analysisOptions :+ inputsOption)*),
      checkConfig(c => if c.command.isEmpty then failure("no command given") else success)
    )

  def main(args: Array[String]): Unit = sys.exit(run(args.toList, System.out, System.err))

  /** runParser returns scopt's output as effects instead of performing them, so
    * everything goes to `out` and `err`, and scopt never exits the JVM itself.
    */
  def run(args: List[String], out: PrintStream, err: PrintStream): Int =
    val (config, effects) = OParser.runParser(parser, args, Config())
    effects.foreach {
      case OEffect.DisplayToOut(msg)  => out.println(msg)
      case OEffect.DisplayToErr(msg)  => err.println(msg)
      case OEffect.ReportError(msg)   => err.println(s"pag: $msg")
      case OEffect.ReportWarning(msg) => err.println(s"pag: warning: $msg")
      case OEffect.Terminate(_)       => ()
    }
    engineGuarded(err) {
      config match
        case Some(c) if c.command.contains("ir") && c.classes.nonEmpty      => ir(c, out, err)
        case Some(c) if c.command.contains("run") && c.classes.nonEmpty     => runProbe(c, out, err)
        case Some(c) if c.command.contains("analyze") && c.classes.nonEmpty => analyze(c, out, err)
        case Some(c) if c.command.contains("check") && c.classes.nonEmpty   => check(c, out, err)
        case _ => 1 // scopt's effects above already reported the error and the usage
    }

  /** An error outside any domain call is the engine's (§7): out of memory or stack
    * is inconclusive, exit 4, never 3. Domain calls catch their own (`Worklist.guard`).
    */
  private[cli] def engineGuarded(err: PrintStream)(body: => Int): Int =
    try body
    catch
      case e: VirtualMachineError =>
        err.println(s"pag: the engine ran out of resources: $e")
        4

  /** Load, check the profile, lift, and print the IR; with --cfg, lower and print the CFG too.
    * Order matters (§5): lifting assumes the profile check passed.
    */
  private def ir(c: Config, out: PrintStream, err: PrintStream): Int =
    withProgram(c.classes.get, c.enforce, err)(()) { (program, _) =>
      val mode = if c.enforce then Lifting.Mode.Strict else Lifting.Mode.Lenient
      val shown = if c.lift then Lifting.lift(program, mode) else program
      Show.program(shown, Profile.BigintMainV1, checked = c.enforce, lifted = c.lift).foreach(out.println)
      if c.cfg then
        out.println()
        Show.cfg(Lowering.lower(shown)).foreach(out.println)
      0
    }

  /** Load, check the profile, lift, lower, and run on the IR interpreter (§9).
    * Always checked and lifted: the interpreter covers v1's lifted form only.
    */
  private def runProbe(c: Config, out: PrintStream, err: PrintStream): Int =
    withProgram(c.classes.get, enforce = true, err)(pag.probe.Inputs.parse(c.inputs)) { (program, inputs) =>
      val lifted = Lifting.lift(program, Lifting.Mode.Strict)
      val run = IrInterpreter.run(Lowering.lower(lifted), inputs, c.stepLimit)
      val main = lifted.methods.find(_.id == lifted.entryMethod).get
      out.println(s"${main.id}   inputs [${inputs.values.asScala.mkString(", ")}] · IR interpreter")
      if c.trace then
        run.visited.foreach {
          case l @ Loc.AppLoc(_, i, true) => out.println(f"  ${Show.loc(l)}%-9s ${Show.cmd(main.body(i))}")
          case l                          => out.println(s"  ${Show.loc(l)}")
        }
      out.println(s"reached   ${if run.reached.isEmpty then "(none)" else run.reached.mkString(" ")}")
      out.println(s"ended     ${ended(run.ended)}")
      0
    }

  /** The front every command shares: check the directory, run `before`, load,
    * and run the profile check unless `enforce` is off; then `body`. `before`
    * runs after the directory check and before loading, under the same error
    * handling, so `run` reports a bad input before it loads anything. Exit 1 for
    * a usage or input error, 2 when the program does not load or violates the
    * profile.
    */
  private def withProgram[A](dir: Path, enforce: Boolean, err: PrintStream)(before: => A)(
      body: (Program, A) => Int
  ): Int =
    if !Files.isDirectory(dir) then
      err.println(s"pag: not a directory: $dir"); 1
    else
      val profile = Profile.BigintMainV1
      try
        val prepared = before
        val program = frontEnd().load(dir)
        val violations = if enforce then ProfileCheck.check(program, profile) else Nil
        if violations.nonEmpty then
          violations.foreach(v => err.println(v.message(program.sourceFile, profile.name)))
          2
        else body(program, prepared)
      catch
        case e: Untranslatable           => err.println(s"pag: ${e.getMessage}"); 2
        case e: IllegalArgumentException => err.println(s"pag: ${e.getMessage}"); 1

  /** Everything `analyze` and `check` share: load, check, lift and lower the
    * program; load the domain; analyze (§7). Always checked and lifted: there is
    * no --no-enforce. `before` is as for `withProgram`.
    */
  private def withAnalysis[A](c: Config, err: PrintStream)(before: => A)(body: (Analyzed, A) => Int): Int =
    withProgram(c.classes.get, enforce = true, err)(before) { (program, prepared) =>
      val lifted = Lifting.lift(program, Lifting.Mode.Strict)
      val lowered = Lowering.lower(lifted)
      val query = Reachable(c.reach.get)
      DomainLoader.load(c.domain.get) match
        case Left(failure) => err.println(s"pag: ${failure.message}"); failure.exitCode
        case Right(d) =>
          Analysis.analyze(d, lowered, query, c.limits) match
            case Left(message) => err.println(s"pag: $message"); 1
            case Right(result) =>
              val targets = Query.resolve(query, lowered).getOrElse(Set.empty) // resolved above; for display
              body(Analyzed(lifted, lowered, domainName(d), query, targets, result), prepared)
    }

  /** One analysis and what it was run on. */
  private final case class Analyzed(
      program: Program,
      lowered: pag.core.Lowered,
      domain: String,
      query: Reachable,
      targets: Set[Loc],
      result: AnalysisResult[?]
  )

  /** Print the invariant map and the verdict (§11). */
  private def analyze(c: Config, out: PrintStream, err: PrintStream): Int =
    withAnalysis(c, err)(()) { (a, _) =>
      AnalyzeReport.lines(c.classes.get, a.program, a.lowered, a.domain, a.query, a.targets, a.result, c.all)
        .foreach(out.println)
      verdictExit(a.result, err)
    }

  /** The reachability check (§9): analyze, then run the class file on the JVM with
    * the given inputs, whatever the verdict, and judge.
    */
  private def check(c: Config, out: PrintStream, err: PrintStream): Int =
    withAnalysis(c, err)(pag.probe.Inputs.parse(c.inputs)) { (a, parsed) =>
      val inputs = parsed.values.asScala.toList.map(BigInt(_))
      val mainClass = a.program.entryMethod.declaringClass
      val started = System.currentTimeMillis()
      val run = JvmRun.run(c.classes.get, mainClass, inputs)
      val runMs = System.currentTimeMillis() - started
      val outcome = Check.judge(a.result.verdict, run.reached, a.query)
      CheckReport.lines(c.classes.get, a.query, a.result, inputs, run, runMs, outcome).foreach(out.println)
      outcome match
        case Outcome.Unsound    => 3
        case Outcome.Consistent => 0
        case Outcome.NoVerdict(_) => verdictExit(a.result, err)
    }

  /** 0 for a verdict, 4 for a limit, 5 for a domain failure with its stack trace on stderr. */
  private def verdictExit(r: AnalysisResult[?], err: PrintStream): Int = r.verdict match
    case Verdict.Inconclusive(Incomplete.DomainFailure(_, e)) =>
      e.printStackTrace(err)
      5
    case Verdict.Inconclusive(_)                              => 4
    case Verdict.Refuted | Verdict.Alarm                      => 0

  /** `name()` is a domain call too; printing must not let it take pag down. */
  private def domainName(d: pag.api.Domain[?]): String =
    try String.valueOf(d.name) catch case e: Throwable => s"(name() threw $e)"

  private def ended(e: Ended): String = e match
    case Ended.Exit               => "exit"
    case Ended.InputsExhausted(l) => s"inputs exhausted at ${Show.loc(l)}"
    case Ended.Stuck(l)           => s"stuck at ${Show.loc(l)}"
    case Ended.StepLimit(n)       => s"step limit $n"

  /** The one front end on the classpath, found through ServiceLoader (§5.5). */
  private def frontEnd(): IrProvider =
    java.util.ServiceLoader.load(classOf[IrProvider]).asScala.toList match
      case List(provider) => provider
      case found          => throw IllegalStateException(s"expected one IrProvider, found ${found.size}")
