package pag.campaign

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import pag.campaign.FakeServer.{completion, withServer}
import pag.cli.DomainJars.apiPath

/** Running a campaign (implementation_strategy.md Phase 10): its inputs pinned on
  * the first run, only missing samples run, and no attempt ever rewritten. The
  * fake model answers without files, so no build is needed and the tests are fast.
  */
class CampaignSuite extends munit.FunSuite:

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)
  val prompt: Prompt = Prompt.assemble(repo, "generator-v1").fold(e => throw IllegalStateException(e), identity)

  def tools(corpus: Path = repo.resolve("corpora/smoke")): Tools = Tools(
    List("java", "-cp", sys.props("java.class.path"), "pag.cli.Main"),
    repo.resolve("domains/build-template"), apiPath,
    Paths.get(classOf[pag.probe.Rand].getProtectionDomain.getCodeSource.getLocation.toURI), corpus)

  /** A results directory, deleted afterwards. */
  def withResults[A](body: Path => A): A =
    val dir = Files.createTempDirectory("results")
    try body(dir) finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  def generate(server: FakeServer, results: Path, samples: Int, temperature: Double = 0.2, p: Prompt = prompt,
      t: Tools = tools()): Either[String, List[SampleOutcome]] =
    val agent = AgentConfig(server.baseUrl, "m.gguf", temperature = temperature, retries = 0)
    Campaign.generate("e1-test", samples, agent, ChatClient(agent), p, t, results)

  def chats(s: FakeServer): Int = s.received.count(_.path.endsWith("/chat/completions"))

  test("a first run pins the campaign's inputs and runs every sample"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val out = generate(s, results, 2).fold(e => fail(e), identity)
        assertEquals(out.collect { case SampleOutcome.Ran(r) => r.attempt }, List("attempt-001", "attempt-002"))
        assert(Files.isRegularFile(results.resolve("e1-test/campaign.json")))
        assert(Files.isRegularFile(results.resolve("e1-test/attempt-002/attempt.json")))
        assertEquals(chats(s), 2)
      }
    }

  test("a later run keeps every existing attempt, byte for byte, and runs only the missing ones"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        generate(s, results, 2).fold(e => fail(e), identity)
        val first = results.resolve("e1-test/attempt-001/attempt.json")
        val before = Files.readAllBytes(first)
        val out = generate(s, results, 3).fold(e => fail(e), identity)
        assertEquals(out.map {
          case SampleOutcome.Kept(a) => s"kept $a"
          case SampleOutcome.Ran(r)  => s"ran ${r.attempt}"
        }, List("kept attempt-001", "kept attempt-002", "ran attempt-003"))
        assert(java.util.Arrays.equals(Files.readAllBytes(first), before), "attempt-001 was rewritten")
        assertEquals(chats(s), 3, "only the new sample asked the model")
      }
    }

  test("a different agent configuration is refused, and nothing runs"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        generate(s, results, 1).fold(e => fail(e), identity)
        val r = generate(s, results, 2, temperature = 0.9)
        assert(r.left.exists(_.contains("the agent configuration changed")), r)
        assertEquals(chats(s), 1)
      }
    }

  test("a different prompt is refused"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        generate(s, results, 1).fold(e => fail(e), identity)
        val edited = prompt.copy(sha256 = "0" * 64)
        assert(generate(s, results, 2, p = edited).left.exists(_.contains("the prompt changed")))
      }
    }

  test("a different corpus is refused"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        generate(s, results, 1).fold(e => fail(e), identity)
        val corpus = Files.createTempDirectory("corpus")
        try
          Using.resource(Files.list(repo.resolve("corpora/smoke")))(_.iterator.asScala.toList)
            .foreach(f => Files.copy(f, corpus.resolve(f.getFileName)))
          Files.writeString(corpus.resolve("Const1.java"), Files.readString(corpus.resolve("Const1.java")) + "\n// edited")
          assert(generate(s, results, 2, t = tools(corpus)).left.exists(_.contains("the corpus changed")))
        finally Using.resource(Files.walk(corpus))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
      }
    }

  test("a bad campaign name or sample count is refused before anything runs"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val agent = AgentConfig(s.baseUrl, "m")
        assert(Campaign.generate("../escape", 1, agent, ChatClient(agent), prompt, tools(), results).isLeft)
        assert(Campaign.generate("ok", 0, agent, ChatClient(agent), prompt, tools(), results).isLeft)
        assertEquals(chats(s), 0)
      }
    }

  test("the setup check points at common.sh when the build is missing"):
    val empty = Files.createTempDirectory("repo")
    try assert(Main.locate(empty).left.exists(_.contains("bash demo_scripts/common.sh")))
    finally Files.delete(empty)
