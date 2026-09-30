package pag.cli

import java.io.PrintStream
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

import pag.core.{Lifting, Lowering, Profile, ProfileCheck}
import pag.ir.{IrProvider, Untranslatable}
import scopt.{OEffect, OParser}

/** The `pag` entry point (implementation_strategy.md §11). Exit codes: 0 done,
  * 1 usage or input error, 2 did not load (untranslatable or profile violation).
  */
object Main:

  /** What the command line asked for. */
  final case class Config(
      command: Option[String] = None,
      classes: Option[Path] = None,
      cfg: Boolean = false,
      lift: Boolean = true,
      enforce: Boolean = true
  )

  private val parser: OParser[Unit, Config] =
    val b = OParser.builder[Config]
    import b.*
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
    config match
      case Some(c @ Config(Some("ir"), Some(_), _, _, _)) => ir(c, out, err)
      case _ => 1 // scopt's effects above already reported the error and the usage

  /** Load, check the profile, lift, and print the IR; with --cfg, lower and print the CFG too.
    * Order matters (§5): lifting assumes the profile check passed.
    */
  private def ir(c: Config, out: PrintStream, err: PrintStream): Int =
    val dir = c.classes.get
    if !Files.isDirectory(dir) then
      err.println(s"pag: not a directory: $dir"); 1
    else
      val profile = Profile.BigintMainV1
      try
        val program = frontEnd().load(dir)
        val violations = if c.enforce then ProfileCheck.check(program, profile) else Nil
        if violations.nonEmpty then
          violations.foreach(v => err.println(v.message(program.sourceFile, profile.name)))
          2
        else
          val mode = if c.enforce then Lifting.Mode.Strict else Lifting.Mode.Lenient
          val shown = if c.lift then Lifting.lift(program, mode) else program
          Show.program(shown, profile, checked = c.enforce, lifted = c.lift).foreach(out.println)
          if c.cfg then
            out.println()
            Show.cfg(Lowering.lower(shown)).foreach(out.println)
          0
      catch
        case e: Untranslatable           => err.println(s"pag: ${e.getMessage}"); 2
        case e: IllegalArgumentException => err.println(s"pag: ${e.getMessage}"); 1

  /** The one front end on the classpath, found through ServiceLoader (§5.5). */
  private def frontEnd(): IrProvider =
    java.util.ServiceLoader.load(classOf[IrProvider]).asScala.toList match
      case List(provider) => provider
      case found          => throw IllegalStateException(s"expected one IrProvider, found ${found.size}")
