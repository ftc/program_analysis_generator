package pag.campaign

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import java.time.Instant
import scala.util.Using

import io.bullet.borer.Json

import pag.campaign.FakeServer.{completion, withServer}
import pag.cli.DomainJars.apiPath

/** Running a campaign (implementation_strategy.md Phase 10, §14): every run in a
  * directory of its own, `<name>-<UTC start>`, so nothing earlier is reused or
  * deleted; an interrupted run continued on request, under its pinned inputs
  * only, running just the missing samples and rewriting none. The fake model
  * answers without files, so no build is needed and the tests are fast.
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

  val at: Instant = Instant.parse("2026-10-09T17:26:00Z")

  def generate(server: FakeServer, results: Path, start: Start, temperature: Double = 0.2, p: Prompt = prompt,
      t: Tools = tools(), when: Instant = at): Either[String, (Path, List[SampleOutcome])] =
    val agent = AgentConfig(server.baseUrl, "m.gguf", temperature = temperature, retries = 0)
    Campaign.generate(start, agent, ChatClient(agent), p, t, results, now = () => when)

  def fresh(server: FakeServer, results: Path, samples: Int, when: Instant = at): Path =
    generate(server, results, Start.Fresh("e1-test", samples), when = when).fold(e => fail(e), _._1)

  def chats(s: FakeServer): Int = s.received.count(_.path.endsWith("/chat/completions"))

  /** Every file under `dir` and its bytes, to show nothing was touched. */
  def snapshot(dir: Path): Map[String, List[Byte]] =
    Using.resource(Files.walk(dir))(_.iterator.asScala.toList).filter(Files.isRegularFile(_))
      .map(f => dir.relativize(f).toString -> Files.readAllBytes(f).toList).toMap

  test("a new run: its own directory, named after the campaign and its UTC start, pinned with its sample count"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val (dir, out) = generate(s, results, Start.Fresh("e1-test", 2)).fold(e => fail(e), identity)
        assertEquals(dir, results.resolve("e1-test-20261009T172600Z"))
        assertEquals(Instant.from(Campaign.StartFormat.parse(dir.getFileName.toString.stripPrefix("e1-test-"))), at)
        assertEquals(out.collect { case SampleOutcome.Ran(r) => r.attempt }, List("attempt-001", "attempt-002"))
        assertEquals(Campaign.pinned(dir).flatMap(_.samples), Some(2))
        assert(Files.isRegularFile(dir.resolve("attempt-002/attempt.json")))
        assertEquals(chats(s), 2)
      }
    }

  test("a second run of the same campaign gets a new directory and leaves the first exactly as it was"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val first = fresh(s, results, 2)
        val before = snapshot(first)
        val second = fresh(s, results, 2, when = at.plusSeconds(3600))
        assertNotEquals(second, first)
        assertEquals(snapshot(first), before)
        assertEquals(chats(s), 4, "the second run reused nothing")
      }
    }

  test("two starts in the same second are refused, and the first run is untouched"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val first = fresh(s, results, 1)
        val before = snapshot(first)
        assert(generate(s, results, Start.Fresh("e1-test", 1)).left.exists(_.contains("already exists")))
        assertEquals((snapshot(first), chats(s)), (before, 1))
      }
    }

  test("resuming keeps every existing attempt, byte for byte, and runs only the missing ones"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val dir = fresh(s, results, 3)
        Using.resource(Files.walk(dir.resolve("attempt-003")))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
        val first = Files.readAllBytes(dir.resolve("attempt-001/attempt.json"))
        val (again, out) = generate(s, results, Start.Resume(dir, None)).fold(e => fail(e), identity)
        assertEquals(again, dir)
        assertEquals(out.map {
          case SampleOutcome.Kept(a) => s"kept $a"
          case SampleOutcome.Ran(r)  => s"ran ${r.attempt}"
        }, List("kept attempt-001", "kept attempt-002", "ran attempt-003"))
        assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve("attempt-001/attempt.json")), first))
        assertEquals(chats(s), 4, "only the missing sample asked the model")
      }
    }

  /** Runs one sample, then resumes under `changed` inputs: refused, naming `what`, touching nothing. */
  def resumeChanged(s: FakeServer, results: Path, what: String, changed: (FakeServer, Path, Path) => Either[String, ?]
  ): Unit =
    val dir = fresh(s, results, 2)
    Using.resource(Files.walk(dir.resolve("attempt-002")))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
    val before = snapshot(dir)
    val out = changed(s, results, dir)
    assert(out.left.exists(e => e.contains(s"$what changed") && e.contains("start a new run")), out)
    assertEquals(snapshot(dir), before)
    assertEquals(chats(s), 2, "only the first run's two samples asked the model")

  test("resuming under a different agent configuration is refused, and nothing is touched"):
    withServer(200 -> completion("no files")) { s =>
      withResults(results => resumeChanged(s, results, "the agent configuration",
        (s, r, d) => generate(s, r, Start.Resume(d, None), temperature = 0.9)))
    }

  test("so is resuming under a different prompt"):
    withServer(200 -> completion("no files")) { s =>
      withResults(results => resumeChanged(s, results, "the prompt",
        (s, r, d) => generate(s, r, Start.Resume(d, None), p = prompt.copy(sha256 = "0" * 64))))
    }

  test("so is resuming under a different corpus"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val corpus = Files.createTempDirectory("corpus")
        try
          Using.resource(Files.list(repo.resolve("corpora/smoke")))(_.iterator.asScala.toList)
            .foreach(f => Files.copy(f, corpus.resolve(f.getFileName)))
          Files.writeString(corpus.resolve("Const1.java"), Files.readString(corpus.resolve("Const1.java")) + "\n// edited")
          resumeChanged(s, results, "the corpus", (s, r, d) => generate(s, r, Start.Resume(d, None), t = tools(corpus)))
        finally Using.resource(Files.walk(corpus))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
      }
    }

  test("resuming a directory that is not a campaign run is refused"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val out = generate(s, results, Start.Resume(results.resolve("nothing-here"), None))
        assert(out.left.exists(_.contains("--resume takes a campaign run's directory")), out)
        assertEquals(chats(s), 0)
      }
    }

  test("a run pinned before sample counts were needs --samples to resume, and only then"):
    import Campaign.given
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val dir = fresh(s, results, 1)
        val pin = dir.resolve("campaign.json")
        Files.write(pin, Json.encode(Campaign.pinned(dir).get.copy(samples = None)).toByteArray)
        assert(generate(s, results, Start.Resume(dir, None)).left.exists(_.contains("resume it with --samples")))
        val (_, out) = generate(s, results, Start.Resume(dir, Some(2))).fold(e => fail(e), identity)
        assertEquals(out.collect { case SampleOutcome.Ran(r) => r.attempt }, List("attempt-002"))
      }
    }

  test("a run that pins its sample count refuses --samples on resuming"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val dir = fresh(s, results, 1)
        assert(generate(s, results, Start.Resume(dir, Some(5))).left.exists(_.contains("without --samples")))
      }
    }

  test("an unreadable pin is an error on resuming, and nothing is touched"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val dir = fresh(s, results, 1)
        Files.writeString(dir.resolve("campaign.json"), "{ not json")
        val before = snapshot(dir)
        assert(generate(s, results, Start.Resume(dir, None)).isLeft)
        assertEquals((snapshot(dir), chats(s)), (before, 1))
      }
    }

  test("a bad campaign name or sample count is refused before anything runs"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        for bad <- List("../escape", ".", "..", "a/b") do
          assert(generate(s, results, Start.Fresh(bad, 1)).isLeft, bad)
        assert(generate(s, results, Start.Fresh("ok", 0)).isLeft)
        assertEquals(chats(s), 0)
        assertEquals(Using.resource(Files.list(results))(_.count()), 0L, "no directory was made")
      }
    }

  test("the committed E1 runs still read as pins, with no sample count recorded"):
    // The pipeline check's pin predates the sampling fields and has never decoded; it is left as it is.
    val pins = Using.resource(Files.list(repo.resolve("results")))(_.iterator.asScala.toList).sorted
      .filter(_.getFileName.toString.startsWith("e1-")).map(d => d -> Campaign.pinned(d))
    assertEquals(pins.size, 3)
    assert(pins.forall(_._2.exists(_.samples.isEmpty)), pins.map((d, p) => s"$d: $p"))

  test("the setup check points at common.sh when the build is missing"):
    val empty = Files.createTempDirectory("repo")
    try assert(Main.locate(empty).left.exists(_.contains("bash scripts/common.sh")))
    finally Files.delete(empty)

  // The build and feedback settings are pinned, and a resume under different ones is refused.

  test("a new run pins this code's build settings and no feedback, visibly in campaign.json"):
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val dir = fresh(s, results, 1)
        val pin = Campaign.pinned(dir).getOrElse(fail("no pin"))
        assertEquals((pin.build, pin.feedback), (Some(BuildPin(120, 3)), None))
        val text = Files.readString(dir.resolve("campaign.json"))
        assert(text.contains("\"timeoutSeconds\": 120") && text.contains("\"compileTries\": 3"), text)
      }
    }

  test("a feedback pin survives campaign.json, its history written as the case's name"):
    import Campaign.given
    for (history, json) <- List(FeedbackHistory.Latest -> "Latest", FeedbackHistory.Full -> "Full") do
      val pin = FeedbackPin(3, history, "feedback-build-v1", "ab" * 32)
      val text = Json.encode(pin).toUtf8String
      assert(text.contains(s"\"history\":\"$json\""), text)
      assertEquals(Json.decode(text.getBytes("UTF-8")).to[FeedbackPin].valueEither, Right(pin))

  /** A one-sample run whose pin is then edited by `edit`; resuming it is refused, naming `what`. */
  def resumeAfterPinEdit(what: String, edit: CampaignPin => CampaignPin): Unit =
    import Campaign.given
    withServer(200 -> completion("no files")) { s =>
      withResults { results =>
        val dir = fresh(s, results, 2)
        Using.resource(Files.walk(dir.resolve("attempt-002")))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
        Files.write(dir.resolve("campaign.json"), Json.encode(edit(Campaign.pinned(dir).get)).toByteArray)
        val before = snapshot(dir)
        val out = generate(s, results, Start.Resume(dir, None))
        assert(out.left.exists(_.contains(s"$what changed")), out)
        assertEquals((snapshot(dir), chats(s)), (before, 2))
      }
    }

  test("resuming a run pinned with other build settings is refused, as it is for the runs from before them"):
    resumeAfterPinEdit("the build settings", _.copy(build = Some(BuildPin(300, 1))))
    resumeAfterPinEdit("the build settings", _.copy(build = None))

  test("resuming a run pinned with feedback, from code running one shot, is refused"):
    resumeAfterPinEdit("the feedback settings",
      _.copy(feedback = Some(FeedbackPin(3, FeedbackHistory.Latest, "feedback-build-v1", "ab" * 32))))

  test("the committed E1 pins have neither build nor feedback settings"):
    val pins = Using.resource(Files.list(repo.resolve("results")))(_.iterator.asScala.toList).sorted
      .filter(_.getFileName.toString.startsWith("e1-")).flatMap(Campaign.pinned)
    assertEquals(pins.map(p => (p.build, p.feedback)), List.fill(3)((None, None)))
