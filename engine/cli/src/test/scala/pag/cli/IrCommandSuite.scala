package pag.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

import pag.frontend.Fixtures

/** `pag ir` end to end: front end through ServiceLoader, profile check,
  * lifting, lowering, printing (implementation_strategy.md §11, Phase 2a).
  */
class IrCommandSuite extends munit.FunSuite:

  final case class Result(exit: Int, out: String, err: String)

  def pag(args: String*): Result =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit = Main.run(args.toList, PrintStream(out, true, UTF_8), PrintStream(err, true, UTF_8))
    Result(exit, out.toString(UTF_8), err.toString(UTF_8))

  def ir(fixture: String, flags: String*): Result =
    Fixtures.withCompiled(fixture)(dir => pag(("ir" :: dir.toString :: flags.toList)*))

  def golden(name: String): String =
    val in = getClass.getResourceAsStream(s"/golden/$name")
    try String(in.readAllBytes(), UTF_8) finally in.close()

  /** Generated once and reviewed by hand against Loop.java; a change here is a
    * change in what pag shows, and needs the same review.
    */
  test("golden: pag ir --cfg on Loop"):
    val r = ir("Loop", "--cfg")
    assertEquals((r.exit, r.err), (0, ""))
    assertEquals(r.out, golden("Loop-cfg.txt"))

  test("--no-lift shows the BigInteger calls, with the same numbering"):
    val lifted = ir("Loop").out.linesIterator.toList
    val raw = ir("Loop", "--no-lift").out.linesIterator.toList
    assertEquals(raw.size, lifted.size)
    assert(raw.head.contains("not lifted"), raw.head)
    assert(raw.exists(_.contains("i.compareTo(")), raw.mkString("\n"))
    assert(raw.exists(_.contains("i.add(")), raw.mkString("\n"))

  test("a program outside the profile exits 2, naming each violation and its setting"):
    val r = ir("Constructs")
    assertEquals(r.exit, 2)
    assertEquals(r.out, "")
    assert(r.err.contains("not allowed by profile bigint-main-v1 (language.mainArgs)"), r.err)
    assert(r.err.contains("(language.rvals)"), r.err)

  test("untranslatable bytecode exits 2"):
    val r = ir("Unsupported")
    assertEquals(r.exit, 2)
    assert(r.err.contains("cannot represent"), r.err)

  test("--no-enforce skips the profile check, for inspection"):
    val r = ir("Constructs", "--no-enforce", "--cfg")
    assertEquals(r.exit, 0, r.err)
    assert(r.out.linesIterator.next().contains("profile not checked"), r.out)

  test("usage and input errors exit 1"):
    assertEquals(pag().exit, 1)
    assertEquals(pag("ir").exit, 1)
    assertEquals(ir("Loop", "--bogus").exit, 1)
    assertEquals(pag("ir", "/no/such/dir").exit, 1)
    assertEquals(ir("TwoClasses").exit, 1)
