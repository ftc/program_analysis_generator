package pag.cli

import scala.jdk.CollectionConverters.*

import pag.core.{Lifting, Lowering, Profile, ProfileCheck}
import pag.frontend.Fixtures
import pag.harness.{Ended, IrInterpreter, JvmRun}
import pag.ir.IrProvider
import pag.probe.Inputs

/** The IR–JVM cross-check (implementation_strategy.md §9, Phase 2b): each
  * fixture runs on the IR interpreter, which runs the lowered Cfg a domain
  * analyses, and as a JVM run, which runs the class file itself. Equal reach
  * sequences and equal endings, over many inputs, are the end-to-end check that
  * loading, lifting and lowering preserve meaning: trust-base claims A and B.
  */
class CrossCheckSuite extends munit.FunSuite:

  val frontEnd: IrProvider = java.util.ServiceLoader.load(classOf[IrProvider]).asScala.head

  /** Each fixture with the input lists it runs on; an empty list runs out at the first randInt. */
  val cases: List[(String, List[List[BigInt]])] = List(
    "CrossArith" -> List(List(0, 0), List(3, 4), List(-5, 2), List(-4, 3), List(5, -10), List(1, 1),
      List(BigInt("98765432109876543210"), BigInt("-12345678901234567890")), List(1)),
    "CrossCompare" -> List(List(1, 2), List(2, 2), List(3, 2), List(-1, -1), List(9, 12), List(-20, -3), Nil),
    "CrossLoop" -> List(List(0), List(1), List(4), List(6), List(-3), Nil)
  )

  for (fixture, inputLists) <- cases do
    test(s"$fixture: the IR interpreter and the JVM agree on every input list"):
      Fixtures.withCompiled(fixture) { classes =>
        val program = frontEnd.load(classes)
        assertEquals(ProfileCheck.check(program, Profile.BigintMainV1), Nil, "fixture must be inside the profile")
        val lowered = Lowering.lower(Lifting.lift(program, Lifting.Mode.Strict))
        var covered = Set.empty[BigInt]
        for inputs <- inputLists do
          val clue = s"$fixture with inputs ${inputs.mkString("[", ", ", "]")}"
          val ir = IrInterpreter.run(lowered, Inputs.parse(inputs.mkString(",")))
          val jvm = JvmRun.run(classes, fixture, inputs)
          assertEquals(ir.reached, jvm.reached, s"$clue: reach ids differ")
          covered ++= jvm.reached
          ir.ended match
            case Ended.Exit =>
              assertEquals((jvm.exitCode, jvm.timedOut), (0, false), s"$clue: IR exited normally\n${jvm.stderr}")
            case Ended.InputsExhausted(_) =>
              assertEquals(jvm.exitCode, 1, s"$clue: IR ran out of inputs")
              assert(jvm.stderr.contains("inputs exhausted"), s"$clue:\n${jvm.stderr}")
            case other =>
              fail(s"$clue: a cross-check fixture must end normally or run out of inputs, not $other")
        // Not vacuous: the inputs drive every reach call at least once.
        assertEquals(covered, lowered.reachSites.keySet, s"$fixture: inputs leave some reach ids unexercised")
      }
