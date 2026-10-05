package pag.cli

import scala.jdk.CollectionConverters.*

import pag.core.{Lifting, Lowering, Profile, ProfileCheck}
import pag.frontend.Fixtures
import pag.harness.{IrInterpreter, JvmRun}
import pag.ir.IrProvider
import pag.probe.Inputs

/** Regression: `DuplicateReach` calls reach(7) in both branches of an if.
  * `Lowered.reachSites` once kept one site per id, so the second overwrote the
  * first and, with x = 1, the JVM printed REACHED-7 while the IR interpreter
  * printed nothing. The profile check's LiteralUnique rule rejects this program,
  * so only --no-enforce (skipped here by hand) gets this far; lowering records
  * every site regardless, and the two executors must still agree.
  */
class DuplicateReachSuite extends munit.FunSuite:

  val frontEnd: IrProvider = java.util.ServiceLoader.load(classOf[IrProvider]).asScala.head

  for x <- List(BigInt(1), BigInt(-1)) do
    test(s"x = $x: the IR interpreter and the JVM print the same reach ids"):
      Fixtures.withCompiled("DuplicateReach") { classes =>
        val program = frontEnd.load(classes)
        assert(ProfileCheck.check(program, Profile.BigintMainV1).nonEmpty, "the profile check must reject it")
        val lowered = Lowering.lower(Lifting.lift(program, Lifting.Mode.Lenient))
        val ir = IrInterpreter.run(lowered, Inputs.parse(x.toString))
        val jvm = JvmRun.run(classes, "DuplicateReach", List(x))
        assertEquals(ir.reached, jvm.reached, s"reachSites = ${lowered.reachSites}")
      }
