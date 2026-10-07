package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Prompt assembly (implementation_strategy.md Phase 10): what goes in, what
  * must not, and a hash that follows every input. Rung 0 of the information
  * ladder (experiments.md E1): the task in general terms, and the contract.
  */
class PromptSuite extends munit.FunSuite:

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)

  def generator(): Prompt = Prompt.assemble(repo, "generator-v1").fold(e => fail(e), identity)

  test("the real prompt: a system and a user message, every slot filled"):
    val p = generator()
    assertEquals(p.messages.map(_.role), List("system", "user"))
    assertEquals(p.version, "generator-v1")
    assert(p.messages.forall(m => !m.content.contains("{{")), "an unfilled slot")
    assert(p.sha256.matches("[0-9a-f]{64}"), p.sha256)

  test("it holds the whole contract"):
    val user = generator().messages(1).content
    for f <- List("BinOp", "Domain", "LVal", "MethodId", "RVal", "Step") do
      assert(user.contains(s"```java pag/api/$f.java\n"), f)

  test("it describes no solution: no kind of domain, no worked example, no worked case"):
    val all = generator().messages.map(_.content).mkString.toLowerCase
    // whole words, so "sign" counts but the contract's "Assign" does not
    for word <- List("interval", "intervals", "sign", "signs", "octagon", "zone", "polyhedra", "constant propagation") do
      assert(!s"\\b$word\\b".r.findFirstIn(all).isDefined, s"the prompt mentions '$word'")
    for text <- List("∈", "ref-", "pag.domains.ref", "[0,10]") do
      assert(!all.contains(text), s"the prompt contains '$text'")

  test("assembling twice gives the same hash"):
    assertEquals(generator().sha256, generator().sha256)

  test("the hash follows every input: each template and a contract file"):
    withRepoCopy { copy =>
      def hash() = Prompt.assemble(copy, "generator-v1").fold(e => fail(e), _.sha256)
      val original = hash()
      val edits = List(
        "campaign/prompts/generator-v1/system.md",
        "campaign/prompts/generator-v1/user.md",
        "engine/api/src/main/java/pag/api/Step.java"
      )
      val hashes = edits.map { f => Files.writeString(copy.resolve(f), Files.readString(copy.resolve(f)) + "\n// edited"); hash() }
      val all = original :: hashes
      assertEquals(all.distinct.size, all.size, "each edit changed the hash")
    }

  test("a slot the assembler does not know is an error naming it"):
    assertEquals(Prompt.fill("a {{contract}} b {{nonsense}}", Map("contract" -> "x")),
      Left("unknown prompt slots: nonsense"))

  test("a missing template is an error"):
    assert(Prompt.assemble(repo, "generator-v0").left.exists(_.contains("missing prompt input")))

  /** The files the prompt reads, copied into a temporary repository and deleted after. */
  def withRepoCopy[A](body: Path => A): A =
    val copy = Files.createTempDirectory("repo")
    try
      for dir <- List("campaign/prompts/generator-v1", "engine/api/src/main/java/pag/api") do
        Using.resource(Files.walk(repo.resolve(dir))) { files =>
          files.iterator.asScala.filter(Files.isRegularFile(_)).foreach { f =>
            val target = copy.resolve(repo.relativize(f))
            Files.createDirectories(target.getParent)
            Files.copy(f, target)
          }
        }
      body(copy)
    finally Using.resource(Files.walk(copy))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
