package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** An experiment file applied to a provenance record (implementation_strategy.md §10): every setting from the
  * experiment file, the model from the record, nothing else; unknown keys refused; the campaign's name derived from
  * both.
  */
class ExperimentSuite extends munit.FunSuite:

  val generator: String =
    """"baseUrl":"http://localhost:8933/v1","temperature":0.6,"topP":0.95,"topK":20,"minP":0.0,
      |"presencePenalty":0.0,"thinking":true,"maxTokens":32768,"timeoutSeconds":2700,"retries":3""".stripMargin

  val experiment: String =
    s"""{"prompt":"generator-v3","samples":5,"feedbackRounds":3,"agents":{"generator":{$generator}}}"""

  /** `$GGUF` stands for the GGUF's absolute path, which `withFiles` fills in. */
  val model: String =
    """{"url":"https://huggingface.co/Qwen/Qwen3.8-27B","file":"Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf",
      |"revision":"1d4bf0f2","sha256":"8ec6796c","path":"$GGUF"}""".stripMargin

  /** A scratch directory holding `config/e1fb-rung0.json`, a provenance record in `config/models/`, and the GGUF it
    * names in `models/`, deleted afterwards. The body gets the directory, the experiment file and the record.
    */
  def withFiles[A](experimentJson: String = experiment, modelJson: String = model, gguf: Boolean = true)(
      body: (Path, Path, Path) => A): A =
    val dir = Files.createTempDirectory("experiment").toAbsolutePath
    try
      val e = Files.createDirectories(dir.resolve("config")).resolve("e1fb-rung0.json")
      val m = Files.createDirectories(dir.resolve("config/models")).resolve("Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.json")
      val ggufPath = Files.createDirectories(dir.resolve("models/Qwen3.8-27B-GGUF"))
        .resolve("Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf")
      Files.writeString(e, experimentJson)
      Files.writeString(m, modelJson.replace("$GGUF", ggufPath.toString))
      if gguf then Files.writeString(ggufPath, "")
      body(dir, e, m)
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  def refused(experimentJson: String = experiment, modelJson: String = model, gguf: Boolean = true): String =
    withFiles(experimentJson, modelJson, gguf)((_, e, m) =>
      Experiment.read(e, m).fold(identity, x => fail(s"expected a refusal, got $x")))

  test("every setting from the experiment file, the model and its source from the provenance record"):
    withFiles() { (dir, e, m) =>
      val x = Experiment.read(e, m).fold(err => fail(err), identity)
      val gguf = dir.resolve("models/Qwen3.8-27B-GGUF/Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf")
      assertEquals(x, Experiment("e1fb-rung0-qwen3.8-27B",
        AgentConfig("http://localhost:8933/v1", gguf.toString, None, 0.6, Some(0.95), Some(20), Some(0.0), Some(0.0),
          Some(true), Some(32768), 2700, 3,
          ModelSource(Some("https://huggingface.co/Qwen/Qwen3.8-27B"), Some("Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf"),
            Some("1d4bf0f2"), Some("8ec6796c"))),
        "generator-v3", 5, 3, None))
    }

  test("without feedbackRounds, one shot; the options left out are not sent"):
    val e = s"""{"prompt":"generator-v3","samples":1,"agents":{"generator":{"baseUrl":"http://x/v1",
               |"temperature":0.2,"timeoutSeconds":60,"retries":0}}}""".stripMargin
    withFiles(experimentJson = e) { (_, e, m) =>
      val x = Experiment.read(e, m).fold(err => fail(err), identity)
      assertEquals(x.feedbackRounds, 0)
      assertEquals((x.agent.topP, x.agent.thinking, x.agent.maxTokens, x.agent.apiKeyEnv), (None, None, None, None))
    }

  test("the model's name is the url's last segment, first letter lowered; a trailing slash is ignored"):
    withFiles(modelJson = model.replace("Qwen3.8-27B\",", "Qwen3.8-27B/\",")) { (_, e, m) =>
      assertEquals(Experiment.read(e, m).map(_.campaign), Right("e1fb-rung0-qwen3.8-27B"))
    }

  test("an unknown key is refused at every level of the experiment file, named by its path"):
    val cases = List(
      experiment.replace("\"feedbackRounds\"", "\"feedbackRound\"") -> "feedbackRound",
      experiment.replace("\"agents\":{", "\"agents\":{\"adversary\":{},") -> "agents.adversary",
      experiment.replace("\"baseUrl\"", "\"model\":\"m.gguf\",\"baseUrl\"") -> "agents.generator.model",
      experiment.replace("\"baseUrl\"", "\"source\":{},\"baseUrl\"") -> "agents.generator.source",
      experiment.replace("\"topK\"", "\"top_k\"") -> "agents.generator.top_k")
    cases.foreach((json, key) => assert(refused(experimentJson = json).contains(s"unknown $key"), key))

  test("each required field of the experiment file is refused when missing, by name"):
    val cases = List(
      experiment.replace("\"prompt\":\"generator-v3\",", "") -> "prompt",
      experiment.replace("\"samples\":5,", "") -> "samples",
      """{"prompt":"generator-v3","samples":5}""" -> "agents",
      experiment.replace("\"baseUrl\":\"http://localhost:8933/v1\",", "") -> "baseUrl",
      experiment.replace("\"temperature\":0.6,", "") -> "temperature",
      experiment.replace("\"timeoutSeconds\":2700,", "") -> "timeoutSeconds",
      experiment.replace(",\"retries\":3", "") -> "retries")
    cases.foreach((json, field) => assert(refused(experimentJson = json).contains(field), field))

  test("a sample count that is not positive is refused"):
    assert(refused(experimentJson = experiment.replace("\"samples\":5", "\"samples\":0")).contains("samples"))

  test("a provenance record without url, file or path, or with an unknown key, is refused"):
    assert(refused(modelJson = """{"file":"Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf"}""").contains("no url"))
    assert(refused(modelJson = """{"url":"https://huggingface.co/Qwen/Qwen3.8-27B"}""").contains("no file"))
    assert(refused(modelJson = model.replace(",\"path\":\"$GGUF\"", "")).contains("no path"))
    assert(refused(modelJson = model.replace("\"revision\"", "\"temperature\":0.6,\"revision\""))
      .contains("unknown temperature"))

  test("a path that is relative, or does not end in the record's file, is refused"):
    assert(refused(modelJson = model.replace("$GGUF", "Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf"))
      .contains("must be absolute"))
    assert(refused(modelJson = model.replace("$GGUF", "/home/s/models/Qwen3.8-27B-GGUF/Qwen3.8-27B-UD-Q6_K_XL.gguf"))
      .contains("must be absolute and name its file"))

  test("a provenance record whose GGUF is not there is refused"):
    assert(refused(gguf = false).contains("there is no GGUF"))

  test("every committed provenance record reads, named after its GGUF, with a revision and hash"):
    val repo = Repo.root().fold(e => fail(e), identity)
    val records = Using.resource(Files.list(repo.resolve("config/models")))(_.iterator.asScala.toList)
    assert(records.nonEmpty)
    records.foreach { r =>
      val m = Experiment.model(r).fold(e => fail(e), identity)
      assertEquals(m.gguf.getFileName.toString, r.getFileName.toString.stripSuffix(".json") + ".gguf", r.toString)
      assert(m.source.revision.isDefined && m.source.sha256.isDefined, r.toString)
    }

  test("a missing experiment file or provenance record is refused, by path"):
    withFiles() { (dir, e, m) =>
      assertEquals(Experiment.read(dir.resolve("none.json"), m), Left(s"no file at ${dir.resolve("none.json")}"))
      assertEquals(Experiment.read(e, dir.resolve("none.json")), Left(s"no file at ${dir.resolve("none.json")}"))
    }

  test("the committed experiment files hold E1's run settings, and each arm's prompt, samples and rounds"):
    val repo = Repo.root().fold(e => fail(e), identity)
    withFiles() { (_, _, m) =>
      val settings = (0.6, Some(0.95), Some(20), Some(0.0), Some(0.0), Some(true), Some(32768), 2700, 3,
        "http://localhost:8933/v1", None)
      List("e1-rung0" -> ("generator-v2", 5, 0), "e1fb-rung0" -> ("generator-v3", 5, 3)).foreach { (arm, expected) =>
        val x = Experiment.read(repo.resolve(s"config/$arm.json"), m).fold(e => fail(e), identity)
        val g = x.agent
        assertEquals((g.temperature, g.topP, g.topK, g.minP, g.presencePenalty, g.thinking, g.maxTokens,
          g.timeoutSeconds, g.retries, g.baseUrl, g.apiKeyEnv), settings, arm)
        assertEquals((x.prompt, x.samples, x.feedbackRounds), expected, arm)
        assertEquals(x.campaign, s"$arm-qwen3.8-27B")
      }
    }

  test("the v4 arm is the 81,920 arm with generator-v4, and the limit its prompt states is its maxTokens"):
    val repo = Repo.root().fold(e => fail(e), identity)
    withFiles() { (_, _, m) =>
      val Seq(base, v4) = List("e1fb82k-rung0", "e1fb82k-rung0-v4")
        .map(arm => Experiment.read(repo.resolve(s"config/$arm.json"), m).fold(e => fail(e), identity)): @unchecked
      assertEquals(v4.agent, base.agent)
      assertEquals((v4.prompt, v4.samples, v4.feedbackRounds), ("generator-v4", base.samples, base.feedbackRounds))
      assertEquals(v4.campaign, "e1fb82k-rung0-v4-qwen3.8-27B")
      val user = Prompt.assemble(repo, v4.prompt).fold(e => fail(e), _.messages(1).content)
      val stated = "limited to ([\\d,]+) tokens".r.findFirstMatchIn(user).map(_.group(1).replace(",", "").toInt)
      assertEquals(stated, v4.agent.maxTokens)
    }

  // Starting a run

  def experimentFor(samples: Int): Experiment =
    Experiment("e1fb-rung0-qwen3.8-27B", AgentConfig("http://x/v1", "m.gguf", retries = 0), "generator-v3", samples, 3, None)

  /** A run directory under a scratch results directory. */
  def withRun[A](name: String)(body: Path => A): A =
    val results = Files.createTempDirectory("results")
    try body(Files.createDirectories(results.resolve(name)))
    finally Using.resource(Files.walk(results))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  test("without --resume, a new run of the derived campaign with the file's sample count"):
    assertEquals(experimentFor(5).start(None), Right(Start.Fresh("e1fb-rung0-qwen3.8-27B", 5)))

  test("a resume of this campaign's run carries the file's sample count, for Campaign to check against the pin"):
    List("e1fb-rung0-qwen3.8-27B-20261012T013725Z", "e1fb-rung0-qwen3.8-27B").foreach { n =>
      withRun(n) { dir =>
        assertEquals(experimentFor(5).start(Some(dir)), Right(Start.Resume(dir, 5)), n)
      }
    }

  test("a resume of another campaign's run is refused, even one whose name extends this one's"):
    List("e1fb-rung0-qwen3.5-27B-20261012T013725Z", "e1fb-rung0-qwen3.8-27B-extra-20261012T013725Z").foreach { n =>
      withRun(n) { dir =>
        assert(experimentFor(5).start(Some(dir)).left.exists(_.contains("is not a run of campaign")), n)
      }
    }

  // llamaCpp: the llama.cpp commit an arm requires its server to be built from.

  test("llamaCpp is read from the experiment file; without it, any server"):
    withFiles(experimentJson = experiment.replace("\"samples\":5,", "\"samples\":5,\"llamaCpp\":\"de7fa0a3c\",")) {
      (_, e, m) => assertEquals(Experiment.read(e, m).map(_.llamaCpp), Right(Some("de7fa0a3c")))
    }
    withFiles() { (_, e, m) => assertEquals(Experiment.read(e, m).map(_.llamaCpp), Right(None)) }

  test("llamaCpp must be a commit hash of at least 7 hex digits"):
    for bad <- List("de7fa0", "master", "DE7FA0A3C") do
      val r = refused(experimentJson = experiment.replace("\"samples\":5,", s"\"samples\":5,\"llamaCpp\":\"$bad\","))
      assert(r.contains("llamaCpp must be a commit hash"), r)

  test("E1's experiment files require llama.cpp de7fa0a3c, the build every committed run used"):
    val repo = Repo.root().fold(e => fail(e), identity)
    for arm <- List("e1-rung0", "e1fb-rung0") do
      assert(Files.readString(repo.resolve(s"config/$arm.json")).contains("\"llamaCpp\": \"de7fa0a3c\""), arm)
