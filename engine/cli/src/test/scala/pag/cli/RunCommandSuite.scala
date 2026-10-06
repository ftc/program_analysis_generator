package pag.cli

import pag.cli.Pag.{Result, golden}
import pag.frontend.Fixtures

/** `pag run`: the pipeline, then the IR interpreter (implementation_strategy.md
  * §9, Phase 2b).
  */
class RunCommandSuite extends munit.FunSuite:

  def run(fixture: String, flags: String*): Result =
    Fixtures.withCompiled(fixture)(dir => Pag(("run" :: dir.toString :: flags.toList)*))

  /** Reviewed by hand: x = 10, so `if x != 10 goto 6` falls through to reach(1). */
  test("golden: pag run --trace on Equals, input 10"):
    val r = run("Equals", "--inputs", "10", "--trace")
    assertEquals((r.exit, r.err), (0, ""))
    assertEquals(r.out, golden("Equals-run-trace.txt"))

  test("an input that fails the test skips the reach"):
    assert(run("Equals", "--inputs", "3").out.contains("reached   (none)\nended     exit"))

  test("running out of inputs ends the run at the randInt call"):
    assert(run("Equals").out.contains("ended     inputs exhausted at pre(1)"))

  test("Loop visits 136 locations, worked out by hand"):
    // 1 entry; pre/post of commands 0-1 (4); 10 iterations of commands 2-7 (10 x 12);
    // the failing check, commands 2-4 (6); commands 8-9 and exit (5)
    val traced = run("Loop", "--trace").out.linesIterator.toList
    assertEquals(traced.count(_.startsWith("  ")), 136)
    assertEquals(traced.takeRight(2), List("reached   1", "ended     exit"))

  test("the step limit stops a run"):
    assert(run("Loop", "--step-limit", "5").out.contains("ended     step limit 5"))

  test("malformed inputs exit 1"):
    val r = run("Equals", "--inputs", "1,x")
    assertEquals(r.exit, 1)
    assert(r.err.contains("not an integer"), r.err)

  test("a program outside the profile exits 2"):
    assertEquals(run("Constructs").exit, 2)
