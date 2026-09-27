package pag.core

/** Module wiring for core, which must not see Soot (§5.5).
  *
  * Mostly a placeholder until core has code of its own. The real boundary is
  * the compile classpath, enforced by the build's checkNoSootOnCompileClasspath
  * before this runs; this only adds that Soot is absent at runtime too.
  */
class CoreModuleSuite extends munit.FunSuite:

  test("soot is not on the classpath"):
    intercept[ClassNotFoundException](Class.forName("soot.G"))
