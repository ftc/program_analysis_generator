package pag.cli

import java.io.PrintStream
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

import pag.core.{Lifting, Lowering, Profile, ProfileCheck}
import pag.ir.{IrProvider, Untranslatable}

/** The `pag` entry point (implementation_strategy.md §11). Exit codes: 0 done,
  * 1 usage or input error, 2 did not load (untranslatable or profile violation).
  */
object Main:

  val usage: String = "usage: pag ir <classes-dir> [--cfg] [--no-lift] [--no-enforce]"

  def main(args: Array[String]): Unit = sys.exit(run(args.toList, System.out, System.err))

  def run(args: List[String], out: PrintStream, err: PrintStream): Int = args match
    case "ir" :: rest => ir(rest, out, err)
    case _            => err.println(usage); 1

  private val IrFlags = Set("--cfg", "--no-lift", "--no-enforce")

  /** Load, check the profile, lift, and print the IR; with --cfg, lower and print the CFG too.
    * Order matters (§5): lifting assumes the profile check passed.
    */
  private def ir(args: List[String], out: PrintStream, err: PrintStream): Int =
    val (flagArgs, paths) = args.partition(_.startsWith("--"))
    val flags = flagArgs.toSet
    if paths.size != 1 || !flags.subsetOf(IrFlags) then
      err.println(usage); 1
    else if !Files.isDirectory(Paths.get(paths.head)) then
      err.println(s"pag: not a directory: ${paths.head}"); 1
    else
      val profile = Profile.BigintMainV1
      val enforce = !flags("--no-enforce")
      val lift = !flags("--no-lift")
      try
        val program = frontEnd().load(Paths.get(paths.head))
        val violations = if enforce then ProfileCheck.check(program, profile) else Nil
        if violations.nonEmpty then
          violations.foreach(v => err.println(v.message(program.sourceFile, profile.name)))
          2
        else
          val mode = if enforce then Lifting.Mode.Strict else Lifting.Mode.Lenient
          val shown = if lift then Lifting.lift(program, mode) else program
          Show.program(shown, profile, checked = enforce, lifted = lift).foreach(out.println)
          if flags("--cfg") then
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
