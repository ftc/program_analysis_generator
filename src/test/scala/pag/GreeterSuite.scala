package pag

class GreeterSuite extends munit.FunSuite:

  test("greets the given name"):
    assertEquals(Greeter.greet("Scala"), "Hello, Scala!")

  test("trims surrounding whitespace"):
    assertEquals(Greeter.greet("  Scala  "), "Hello, Scala!")

  test("falls back to world when the name is blank"):
    assertEquals(Greeter.greet("   "), "Hello, world!")
