package pag.frontend

/** Module wiring for frontend-soot, the one module that compiles against Soot
  * (§5.5).
  *
  * A placeholder until the front end exists, and the control for the negative
  * checks in core and harness: the same lookup succeeds here, so theirs do not
  * pass for a trivial reason.
  */
class FrontendSootModuleSuite extends munit.FunSuite:

  test("soot is on the classpath"):
    assertEquals(Class.forName("soot.G").getName, "soot.G")
