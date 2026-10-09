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
