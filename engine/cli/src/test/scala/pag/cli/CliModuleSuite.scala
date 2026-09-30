package pag.cli

/** Module wiring for cli, which reaches frontend-soot at runtime only (§5.5).
  *
  * This one does real work. The build's checkNoSootOnCompileClasspath proves
  * Soot is absent at compile time; this proves the runtime-only dependency
  * still delivers it at runtime. Without it, the ServiceLoader lookup for the
  * front end would silently find nothing, and no compile-time check would
  * notice.
  */
class CliModuleSuite extends munit.FunSuite:

  test("soot is on the runtime classpath"):
    assertEquals(Class.forName("soot.G").getName, "soot.G")

  /** cli cannot name SootIrProvider at compile time; it must find it this way (§5.5). */
  test("the front end is found through ServiceLoader"):
    import scala.jdk.CollectionConverters.*
    val providers = java.util.ServiceLoader.load(classOf[pag.ir.IrProvider]).asScala.toList
    assertEquals(providers.map(_.getClass.getName), List("pag.frontend.SootIrProvider"))
