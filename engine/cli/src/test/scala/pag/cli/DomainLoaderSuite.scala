package pag.cli

import java.nio.file.{Files, Path}
import pag.api.Domain
import pag.cli.DomainJars.*

/** DomainLoader (implementation_strategy.md §11): the discovery rule, its
  * failures and their exit codes, on jars compiled in the test against api.jar.
  */
class DomainLoaderSuite extends munit.FunSuite:

  def load(jar: Path): Either[DomainLoadFailure, Domain[Any]] = DomainLoader.load(jar)
  def failure(jar: Path): DomainLoadFailure = load(jar).fold(identity, d => fail(s"loaded ${d.name}"))

  test("the reference interval domain loads from its own sources"):
    withJar(intervalSources) { jar =>
      assertEquals(load(jar).map(_.name), Right("ref-interval"))
    }

  test("a jar with no domain is a usage error"):
    withJar(Map("stub/Helper.java" -> "package stub; public class Helper {}")) { jar =>
      val f = failure(jar)
      assertEquals(f.exitCode, 1)
      assert(f.message.contains("no public, concrete class implements pag.api.Domain"), f.message)
    }

  test("two domains is a usage error naming both"):
    withJar(Map(stub("A"), stub("B"))) { jar =>
      val f = failure(jar)
      assertEquals(f.exitCode, 1)
      assert(f.message.contains("stub.A, stub.B"), f.message)
    }

  test("abstract and non-public implementations do not count"):
    val abstractOne = "stub/Base.java" -> "package stub; public abstract class Base implements pag.api.Domain<Object> {}"
    withJar(Map(abstractOne, stub("Hidden", modifiers = ""), stub("Real"))) { jar =>
      assertEquals(load(jar).map(_.name), Right("Real"))
    }

  test("no public constructor taking no arguments is a usage error"):
    withJar(Map(stub("NeedsArg", body = "public NeedsArg(int n) {}"))) { jar =>
      val f = failure(jar)
      assertEquals(f.exitCode, 1)
      assert(f.message.contains("no public constructor taking no arguments"), f.message)
    }

  test("a constructor that throws is a domain failure, exit 5, with the cause"):
    withJar(Map(stub("Throws", body = """public Throws() { throw new IllegalStateException("boom"); }"""))) { jar =>
      val f = failure(jar)
      assertEquals(f.exitCode, 5)
      assert(f.message.contains("IllegalStateException: boom"), f.message)
    }

  test("a static initializer that throws is a domain failure, exit 5"):
    withJar(Map(stub("BadInit", body = """static { if (true) throw new IllegalStateException("static boom"); }"""))) {
      jar =>
        val f = failure(jar)
        assertEquals(f.exitCode, 5)
        assert(f.message.contains("ExceptionInInitializerError"), f.message)
    }

  test("scanning initializes nothing: a helper whose static initializer throws is harmless"):
    val helper = "stub/Bomb.java" -> """package stub; public class Bomb { static { if (true) throw new RuntimeException("scanned"); } }"""
    withJar(Map(helper, stub("Fine"))) { jar =>
      assertEquals(load(jar).map(_.name), Right("Fine"))
    }

  test("a jar bundling its own pag.api still gets the engine's: the contract types agree"):
    withJar(Map(stub("Bundled")), extra = apiClasses) { jar =>
      val d = load(jar).fold(f => fail(f.message), identity)
      // the domain's Domain interface is the engine's own class, not the jar's copy
      assert(d.getClass.getInterfaces.contains(classOf[Domain[?]]))
      assertEquals(d.name, "Bundled")
    }

  test("a missing file or a non-jar is a usage error"):
    assertEquals(failure(Path.of("/no/such/domain.jar")).exitCode, 1)
    val notJar = Files.createTempFile("not", ".jar")
    try
      Files.writeString(notJar, "not a jar")
      assertEquals(failure(notJar).exitCode, 1)
    finally Files.delete(notJar)
