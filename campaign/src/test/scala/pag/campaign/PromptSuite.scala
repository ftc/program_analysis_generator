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

  /** Every version stays checked: v1 is superseded but its campaign's records refer to it. */
  val versions: List[String] = List("generator-v1", "generator-v2", "generator-v3", "generator-v4")
  val current: String = "generator-v4"

  def generator(version: String): Prompt = Prompt.assemble(repo, version).fold(e => fail(e), identity)

  for version <- versions do
    test(s"$version: a system and a user message, every slot filled"):
      val p = generator(version)
      assertEquals(p.messages.map(_.role), List("system", "user"))
      assertEquals(p.version, version)
      assert(p.messages.forall(m => !m.content.contains("{{")), "an unfilled slot")
      assert(p.sha256.matches("[0-9a-f]{64}"), p.sha256)

    test(s"$version: it holds the whole contract"):
      val user = generator(version).messages(1).content
      for f <- List("BinOp", "Domain", "LVal", "MethodId", "RVal", "Step") do
        assert(user.contains(s"```java pag/api/$f.java\n"), f)

    test(s"$version: it describes no solution: no kind of domain, no worked example, no worked case"):
      val all = generator(version).messages.map(_.content).mkString.toLowerCase
      // whole words, so "sign" counts but the contract's "Assign" does not
      for word <- List("interval", "intervals", "sign", "signs", "octagon", "zone", "polyhedra", "constant propagation") do
        assert(s"\\b$word\\b".r.findFirstIn(all).isEmpty, s"the prompt mentions '$word'")
      for text <- List("∈", "ref-", "pag.domains.ref", "[0,10]") do
        assert(!all.contains(text), s"the prompt contains '$text'")

  test("v2 differs from v1 only in the example's file name, a placeholder rather than a class name"):
    val Seq(v1, v2) = List("generator-v1", "generator-v2").map(v => Files.readString(repo.resolve(s"campaign/prompts/$v/system.md"))): @unchecked
    assertEquals(v2, v1.replace("Example.java", "<YourDomain>.java"))
    assertEquals(Files.readString(repo.resolve("campaign/prompts/generator-v2/user.md")),
      Files.readString(repo.resolve("campaign/prompts/generator-v1/user.md")))

  test("v3 keeps v2's system message"):
    assertEquals(Files.readString(repo.resolve("campaign/prompts/generator-v3/system.md")),
      Files.readString(repo.resolve("campaign/prompts/generator-v2/system.md")))

  test("v3's prompt is unchanged: its hash is the one its campaigns recorded"):
    assertEquals(generator("generator-v3").sha256, "ccdfeebef66b775ce3df0e7f747b0b49ce94615e585835405bfe2d2169dd9bbe")

  test("v4 keeps v3's system message"):
    assertEquals(Files.readString(repo.resolve("campaign/prompts/generator-v4/system.md")),
      Files.readString(repo.resolve("campaign/prompts/generator-v3/system.md")))

  test("v4's user message is v3's with one section, how to work, before the reply section"):
    val Seq(v3, v4) = List("generator-v3", "generator-v4").map(v => Files.readString(repo.resolve(s"campaign/prompts/$v/user.md"))): @unchecked
    val section = "(?s)# How to work\n.*?\n\n(?=# Your reply\n)".r.findFirstIn(v4).getOrElse(fail("no section"))
    assertEquals(v4.replace(section, ""), v3)
    assertEquals("(?m)^# ".r.findAllIn(section).size, 1, "exactly one heading added")

  for version <- List("generator-v3", "generator-v4") do
    test(s"$version's skeleton, its placeholder filled in, compiles against the contract"):
      val user = generator(version).messages(1).content
      val skeleton = "```java\n(?s)(.*?)\n```".r.findAllMatchIn(user).map(_.group(1))
        .filter(_.contains("implements Domain<<YourDomain>.State>")).toList
      assertEquals(skeleton.size, 1, "exactly one skeleton block")
      val source = skeleton.head.replace("<YourDomain>", "SkeletonDomain")
      val errors = compile(Map("pag/domains/gen/SkeletonDomain.java" -> source))
      assertEquals(errors, Nil)

  test("the compile check itself rejects the mistake the skeleton guards against"):
    val source = "package pag.domains.gen;\nimport pag.api.*;\n" +
      "public abstract class Bad implements Domain<State> { public static final class State {} }"
    assert(compile(Map("pag/domains/gen/Bad.java" -> source)).exists(_.contains("cannot find symbol")))

  test("assembling twice gives the same hash"):
    assertEquals(generator(current).sha256, generator(current).sha256)

  test("the hash follows every input: each template and a contract file"):
    withRepoCopy { copy =>
      def hash() = Prompt.assemble(copy, current).fold(e => fail(e), _.sha256)
      val original = hash()
      val edits = List(
        s"campaign/prompts/$current/system.md",
        s"campaign/prompts/$current/user.md",
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

  /** javac's errors compiling `sources` (path -> text) together with `engine/api`'s sources. */
  def compile(sources: Map[String, String]): List[String] =
    val dir = Files.createTempDirectory("skeleton")
    try
      val written = sources.toList.map { (path, text) =>
        val f = dir.resolve("src").resolve(path)
        Files.createDirectories(f.getParent)
        Files.writeString(f, text)
      }
      val api = Using.resource(Files.walk(repo.resolve("engine/api/src/main/java"))) { files =>
        files.iterator.asScala.filter(_.toString.endsWith(".java")).toList
      }
      val javac = javax.tools.ToolProvider.getSystemJavaCompiler
      val diagnostics = javax.tools.DiagnosticCollector[javax.tools.JavaFileObject]()
      Using.resource(javac.getStandardFileManager(diagnostics, null, null)) { fm =>
        val units = fm.getJavaFileObjectsFromPaths((written ++ api).asJava)
        val options = List("--release", "21", "-d", dir.resolve("out").toString).asJava
        javac.getTask(null, fm, diagnostics, options, null, units).call()
      }
      diagnostics.getDiagnostics.asScala.toList
        .filter(_.getKind == javax.tools.Diagnostic.Kind.ERROR)
        .map(d => s"${d.getSource.getName}:${d.getLineNumber}: ${d.getMessage(null)}")
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  /** The files the prompt reads, copied into a temporary repository and deleted after. */
  def withRepoCopy[A](body: Path => A): A =
    val copy = Files.createTempDirectory("repo")
    try
      for dir <- List(s"campaign/prompts/$current", "engine/api/src/main/java/pag/api") do
        Using.resource(Files.walk(repo.resolve(dir))) { files =>
          files.iterator.asScala.filter(Files.isRegularFile(_)).foreach { f =>
            val target = copy.resolve(repo.relativize(f))
            Files.createDirectories(target.getParent)
            Files.copy(f, target)
          }
        }
      body(copy)
    finally Using.resource(Files.walk(copy))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
