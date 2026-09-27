package pag.cli

/** The `pag` entry point (implementation_strategy.md §11). No subcommands yet. */
object Main:

  val usage: String = "usage: pag <command> [options]   (no commands yet)"

  def main(args: Array[String]): Unit =
    System.err.println(usage)
    sys.exit(1) // exit code 1: usage error (§11)
