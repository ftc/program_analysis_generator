package pag.campaign

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Reading files out of a reply (implementation_strategy.md Phase 10). The
  * files come from a model and are written to disk, so unsafe paths are refused.
  */
class ReplySuite extends munit.FunSuite:

  val fence: String = "```"
  def block(info: String, body: String): String = s"$fence$info\n$body\n$fence"

  test("files are the fenced blocks naming a .java path; prose and other blocks are skipped"):
    val reply = List(
      "Here is the domain.",
      block("java src/pag/domains/gen/A.java", "package pag.domains.gen;\n\nclass A {}"),
      block("bash", "./gradlew build"),
      block("java", "int x = 1;"),
      block("java test/pag/domains/gen/ATest.java", "class ATest {}"),
      "Hope that helps."
    ).mkString("\n\n")
    val r = Reply.files(reply)
    assertEquals(r.files, Map(
      "src/pag/domains/gen/A.java" -> "package pag.domains.gen;\n\nclass A {}",
      "test/pag/domains/gen/ATest.java" -> "class ATest {}"))
    assertEquals((r.ignoredBlocks, r.problems), (2, Nil))

  test("the path may stand alone after the fence, without the language"):
    assertEquals(Reply.files(block(" src/X.java", "class X {}")).files.keySet, Set("src/X.java"))

  test("paths that could write anywhere but src/ or test/ are refused, and never returned"):
    val bad = List("../evil.java", "/src/A.java", "src/../../A.java", "lib/A.java", "src/A.txt.sh",
      "src//A.java", "src/./A.java", "test/../src/A.java")
    val r = Reply.files(bad.map(p => block(s"java $p", "class A {}")).mkString("\n"))
    assertEquals(r.files, Map.empty[String, String])
    assertEquals(r.problems.size, bad.count(_.endsWith(".java")), r.problems)
    assert(r.problems.forall(_.startsWith("refused path: ")), r.problems)

  test("a file given twice is a problem, and neither copy is used"):
    val r = Reply.files(block("java src/A.java", "v1") + "\n" + block("java src/A.java", "v2"))
    assertEquals((r.files, r.problems), (Map.empty[String, String], List("given twice: src/A.java")))

  val junitTest: String = "import org.junit.jupiter.api.Test;\nclass ATest { @Test void t() {} }"

  test("a file under src/test/ is refused as a test under src/, whatever it imports"):
    val r = Reply.files(block("java src/A.java", "class A {}") + "\n" + block("java src/test/pag/ATest.java", "class ATest {}"))
    assertEquals((r.files.keySet, r.problems), (Set("src/A.java"), List("test file under src/: src/test/pag/ATest.java")))

  test("a file under src/ importing JUnit is refused as a test under src/"):
    val plain = block("java src/pag/ATest.java", junitTest)
    val static = block("java src/pag/BTest.java", "import static org.junit.jupiter.api.Assertions.*;\nclass BTest {}")
    val r = Reply.files(plain + "\n" + static)
    assertEquals(r.files, Map.empty[String, String])
    assertEquals(r.problems, List("test file under src/: src/pag/ATest.java", "test file under src/: src/pag/BTest.java"))

  test("a src/ file named like a test but without JUnit is kept; a JUnit test under test/ is kept"):
    val r = Reply.files(block("java src/pag/ATest.java", "class ATest {}") + "\n" + block("java test/pag/ATest.java", junitTest))
    assertEquals(r.files, Map("src/pag/ATest.java" -> "class ATest {}", "test/pag/ATest.java" -> junitTest))
    assertEquals(r.problems, Nil)

  test("JUnit mentioned in a comment or a string, not imported, does not refuse a src/ file"):
    val text = "// unlike org.junit.Assert, this checks nothing\nclass A { String s = \"import org.junit.X;\"; }"
    assertEquals(Reply.files(block("java src/A.java", text)).files, Map("src/A.java" -> text))

  test("an unclosed block is a problem; files before it are kept"):
    val r = Reply.files(block("java src/A.java", "class A {}") + "\n" + fence + "java src/B.java\nclass B {")
    assertEquals(r.files.keySet, Set("src/A.java"))
    assertEquals(r.problems, List("an unclosed code block at line 4")) // the first block is lines 1-3

  test("a path on the block's first line is read too, bare or as a comment, and not kept in the file"):
    val bare = fence + "java\nsrc/pag/domains/gen/A.java\npackage pag.domains.gen;\nclass A {}\n" + fence
    val comment = fence + "java\n// test/pag/domains/gen/ATest.java\nclass ATest {}\n" + fence
    val r = Reply.files(bare + "\n" + comment)
    assertEquals(r.files, Map(
      "src/pag/domains/gen/A.java" -> "package pag.domains.gen;\nclass A {}",
      "test/pag/domains/gen/ATest.java" -> "class ATest {}"))
    assertEquals((r.ignoredBlocks, r.problems), (0, Nil))

  test("a first line that only mentions a file is code, not a path"):
    val r = Reply.files(fence + "java\n// see Example.java for how\nclass X {}\n" + fence)
    assertEquals((r.files, r.ignoredBlocks), (Map.empty[String, String], 1))

  test("an unsafe path on the first line is refused like any other"):
    val r = Reply.files(fence + "java\n../escape.java\nclass X {}\n" + fence)
    assertEquals((r.files, r.problems), (Map.empty[String, String], List("refused path: ../escape.java")))

  test("generator-v2's example names no class, and parses once the placeholder is filled in"):
    val repo = Repo.root().fold(e => throw IllegalStateException(e), identity)
    val system = Files.readString(repo.resolve("campaign/prompts/generator-v2/system.md"))
    assert(!system.contains("Example.java"), "v2 gives no example class name")
    assertEquals(Reply.files(system.replace("<YourDomain>", "IntervalDomain")).files.keySet,
      Set("src/pag/domains/gen/IntervalDomain.java"))

  test("the system prompt's own example block parses as a file (generator-v1)"):
    val repo = Repo.root().fold(e => throw IllegalStateException(e), identity)
    val system = Files.readString(repo.resolve("campaign/prompts/generator-v1/system.md"))
    assertEquals(Reply.files(system).files.keySet, Set("src/pag/domains/gen/Example.java"))

  test("ref-sign, written in the requested format, parses back to exactly its files"):
    val repo = Repo.root().fold(e => throw IllegalStateException(e), identity)
    val domain = repo.resolve("domains/ref-sign")
    val files: Map[String, String] = Using.resource(Files.walk(domain)) { all =>
      all.iterator.asScala.filter(_.toString.endsWith(".java")).filterNot(_.toString.contains("/build/"))
        .map(f => domain.relativize(f).toString -> Files.readString(f).stripTrailing).toMap
    }
    val reply = files.toList.sorted.map((p, text) => block(s"java $p", text)).mkString("Here you go.\n\n", "\n\n", "\n")
    val r = Reply.files(reply)
    assertEquals((r.files, r.problems), (files, Nil))

  // Reply.Rules names these rules in every campaign's pin, so a change to what `files` returns must change it.

  /** Replies exercising every rule above, and the model's replies in the committed one-shot runs. */
  val samples: List[String] =
    val repo = Repo.root().fold(e => throw IllegalStateException(e), identity)
    val committed = List("0.8B", "9B", "27B").flatMap { size =>
      val dir = repo.resolve(s"results/e1-rung0-qwen3.5-$size")
      Using.resource(Files.list(dir))(_.iterator.asScala.toList).sorted.map(_.resolve("attempt.json"))
        .filter(Files.isRegularFile(_)).flatMap(f => Attempt.read(f).fold(e => fail(e), identity).reply).map(_.content)
    }
    List(
      block("java src/pag/domains/gen/A.java", "class A {}") + "\n" + block("bash", "ls") + "\n" + block("java", "int x;"),
      block("java", "src/B.java\nclass B {}") + "\n" + block("java", "// test/BTest.java\nclass BTest {}"),
      block("java src/A.java", "v1") + "\n" + block("java src/A.java", "v2"),
      block("java ../escape.java", "x") + "\n" + block("java /abs/X.java", "x") + "\n" + block("java src/x.txt", "x"),
      block("java src/test/pag/ATest.java", "class ATest {}") + "\n" + block("java src/pag/BTest.java", junitTest),
      block("java src/pag/CTest.java", "class CTest {}") + "\n" + block("java test/pag/CTest.java", junitTest),
      block("java src/A.java", "class A {}") + "\n" + fence + "java src/B.java\nclass B {"
    ) ++ committed

  /** Everything `files` returns for every sample, hashed. */
  def fingerprint: String =
    val text = samples.map { reply =>
      val r = Reply.files(reply)
      (r.files.toList.sorted.map((p, t) => s"$p\n$t") ++ List(r.ignoredBlocks.toString) ++ r.problems).mkString("\u0000")
    }.mkString("\u0001")
    Prompt.sha256(text.getBytes("UTF-8"))

  test("the samples include the replies of the 15 committed one-shot attempts"):
    assertEquals(samples.size, 7 + 15)

  // If this fails: when the rules changed, bump Reply.Rules and set its new fingerprint here; when only the samples
  // changed, the fingerprint alone changes, under the same version.
  test("Reply.Rules is the version whose fingerprint these rules give"):
    assertEquals((Reply.Rules, fingerprint), ("v2", "6a2e3a22c1c94f944a9105c0a183f46c77de8962e314fd64485d2510a6297c0e"))
