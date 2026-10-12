package pag.campaign

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import io.bullet.borer.Json

/** An experiment file applied to a model file (implementation_strategy.md §10): every setting from the experiment
  * file, the model from the model file, nothing else; unknown keys refused; the campaign's name derived from both.
  */
class ExperimentSuite extends munit.FunSuite:

  val generator: String =
    """"baseUrl":"http://localhost:8933/v1","temperature":0.6,"topP":0.95,"topK":20,"minP":0.0,
      |"presencePenalty":0.0,"thinking":true,"maxTokens":32768,"timeoutSeconds":2700,"retries":3""".stripMargin

  val experiment: String =
    s"""{"prompt":"generator-v3","samples":5,"feedbackRounds":3,"agents":{"generator":{$generator}}}"""

  val model: String =
    """{"url":"https://huggingface.co/Qwen/Qwen3.8-27B","file":"Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf",
      |"revision":"1d4bf0f2","sha256":"8ec6796c"}""".stripMargin

  /** A scratch directory holding `config/e1fb-rung0.json`, a model file and the GGUF it names, deleted afterwards.
    * The body gets the directory, the experiment file and the model file.
    */
  def withFiles[A](experimentJson: String = experiment, modelJson: String = model, gguf: Boolean = true)(
      body: (Path, Path, Path) => A): A =
    val dir = Files.createTempDirectory("experiment")
    try
      val e = Files.createDirectories(dir.resolve("config")).resolve("e1fb-rung0.json")
      val models = Files.createDirectories(dir.resolve("models/Qwen3.8-27B-GGUF"))
      val m = models.resolve("Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.source.json")
      Files.writeString(e, experimentJson)
      Files.writeString(m, modelJson)
      if gguf then Files.writeString(models.resolve("Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf"), "")
      body(dir, e, m)
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  def refused(experimentJson: String = experiment, modelJson: String = model, gguf: Boolean = true): String =
    withFiles(experimentJson, modelJson, gguf)((_, e, m) =>
      Experiment.read(e, m).fold(identity, x => fail(s"expected a refusal, got $x")))

  test("every setting from the experiment file, the model and its source from the model file"):
    withFiles() { (dir, e, m) =>
      val x = Experiment.read(e, m).fold(err => fail(err), identity)
      val gguf = dir.toAbsolutePath.resolve("models/Qwen3.8-27B-GGUF/Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf")
      assertEquals(x, Experiment("e1fb-rung0-qwen3.8-27B",
        AgentConfig("http://localhost:8933/v1", gguf.toString, None, 0.6, Some(0.95), Some(20), Some(0.0), Some(0.0),
          Some(true), Some(32768), 2700, 3,
          ModelSource(Some("https://huggingface.co/Qwen/Qwen3.8-27B"), Some("Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf"),
            Some("1d4bf0f2"), Some("8ec6796c"))),
        "generator-v3", 5, 3))
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

  test("a model file without url or file, or with an unknown key, is refused"):
    assert(refused(modelJson = """{"file":"Qwen3.8-27B-Q8_0-llamacpp-de7fa0a3c.gguf"}""").contains("no url"))
    assert(refused(modelJson = """{"url":"https://huggingface.co/Qwen/Qwen3.8-27B"}""").contains("no file"))
    assert(refused(modelJson = model.replace("\"revision\"", "\"temperature\":0.6,\"revision\""))
      .contains("unknown temperature"))

  test("a model file naming a GGUF that is not there is refused"):
    assert(refused(gguf = false).contains("there is no"))

  test("a missing experiment or model file is refused, by path"):
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

  // Starting a run

  def experimentFor(samples: Int): Experiment =
    Experiment("e1fb-rung0-qwen3.8-27B", AgentConfig("http://x/v1", "m.gguf", retries = 0), "generator-v3", samples, 3)

  /** A run directory under a scratch results directory, holding a pin with this sample count (none: no pin). */
  def withRun[A](name: String, pinnedSamples: Option[Option[Int]])(body: Path => A): A =
    val results = Files.createTempDirectory("results")
    try
      val dir = Files.createDirectories(results.resolve(name))
      pinnedSamples.foreach { samples =>
        import Campaign.given
        val pin = CampaignPin(AgentRecord.of(experimentFor(5).agent), "generator-v3", "p", "c", "profile",
          samples = samples)
        Files.write(dir.resolve("campaign.json"), Json.encode(pin).toByteArray)
      }
      body(dir)
    finally Using.resource(Files.walk(results))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  test("without --resume, a new run of the derived campaign with the file's sample count"):
    assertEquals(experimentFor(5).start(None), Right(Start.Fresh("e1fb-rung0-qwen3.8-27B", 5)))

  test("a resume of this campaign's run, pinned with the file's sample count, keeps the pinned count"):
    withRun("e1fb-rung0-qwen3.8-27B-20261012T013725Z", Some(Some(5))) { dir =>
      assertEquals(experimentFor(5).start(Some(dir)), Right(Start.Resume(dir, None)))
    }

  test("a resume whose pinned sample count differs from the file's is refused"):
    withRun("e1fb-rung0-qwen3.8-27B-20261012T013725Z", Some(Some(5))) { dir =>
      assert(experimentFor(3).start(Some(dir)).left.exists(_.contains("pins 5 samples")))
    }

  test("a run pinned before sample counts were, named after the campaign alone, takes the file's count"):
    withRun("e1fb-rung0-qwen3.8-27B", Some(None)) { dir =>
      assertEquals(experimentFor(5).start(Some(dir)), Right(Start.Resume(dir, Some(5))))
    }

  test("a resume of another campaign's run is refused, even one whose name extends this one's"):
    List("e1fb-rung0-qwen3.5-27B-20261012T013725Z", "e1fb-rung0-qwen3.8-27B-extra-20261012T013725Z").foreach { n =>
      withRun(n, Some(Some(5))) { dir =>
        assert(experimentFor(5).start(Some(dir)).left.exists(_.contains("is not a run of campaign")), n)
      }
    }
