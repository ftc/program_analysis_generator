package pag.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

import pag.cli.DomainJars.withJar
import pag.frontend.Fixtures

/** Running `pag` in-process for the command suites. */
object Pag:

  final case class Result(exit: Int, out: String, err: String)

  def apply(args: String*): Result =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit = Main.run(args.toList, PrintStream(out, true, UTF_8), PrintStream(err, true, UTF_8))
    Result(exit, out.toString(UTF_8), err.toString(UTF_8))

  /** `pag <command> --domain <jar of sources> --classes <fixture> <flags>`. */
  def withDomain(command: String, fixture: String, sources: Map[String, String], flags: String*): Result =
    withJar(sources) { jar =>
      Fixtures.withCompiled(fixture) { dir =>
        Pag((List(command, "--domain", jar.toString, "--classes", dir.toString) ++ flags)*)
      }
    }

  def golden(name: String): String =
    val in = getClass.getResourceAsStream(s"/golden/$name")
    try String(in.readAllBytes(), UTF_8) finally in.close()
