package pag.harness

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*

/** The JVM run (implementation_strategy.md §9), on probe-lib's compiled fixtures
  * (pag.probe.fixtures.Fixtures), whose behaviour is fixed by their source.
  */
class JvmRunSuite extends munit.FunSuite:

  /** probe-lib's test classes, which hold the fixtures. */
  val fixtures: Path =
    Paths.get(classOf[pag.probe.fixtures.Fixtures].getProtectionDomain.getCodeSource.getLocation.toURI)

  def run(fixture: String, inputs: List[BigInt], timeout: FiniteDuration = JvmRun.DefaultTimeout): JvmRunResult =
    JvmRun.run(fixtures, s"pag.probe.fixtures.Fixtures$$$fixture", inputs, timeout)

  test("markers are reported in order, and a normal end exits 0"):
    // ReachRandReach: reach(1); randInt(); reach(2)
    assertEquals(run("ReachRandReach", List(5)), JvmRunResult(Vector(1, 2), 0, timedOut = false, ""))

  test("running out of inputs keeps the markers before it, and exits 1"):
    val r = run("ReachRandReach", Nil)
    assertEquals((r.reached, r.exitCode, r.timedOut), (Vector(BigInt(1)), 1, false))
    assert(r.stderr.contains("inputs exhausted after 0 value(s)"), r.stderr)

  test("inputs of any size reach the probe"):
    assertEquals(run("ReachRandReach", List(BigInt("123456789012345678901234567890"))).reached, Vector[BigInt](1, 2))

  test("a probe that never ends is killed at the timeout, and its marker survives"):
    // ReachThenBlock: reach(7), then sleeps forever
    val r = run("ReachThenBlock", Nil, 2.seconds)
    assertEquals((r.reached, r.timedOut, r.exitCode), (Vector(BigInt(7)), true, 137))

  test("stdout that is not a marker is ignored"):
    // EchoFourInputs prints its inputs, which are not markers
    assertEquals(run("EchoFourInputs", List(1, 2, 3, 4)).reached, Vector.empty[BigInt])
