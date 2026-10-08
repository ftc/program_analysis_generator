package pag.campaign

/** The campaign config (implementation_strategy.md §10): JSON, optional fields may be left out. */
class ConfigSuite extends munit.FunSuite:

  test("the full form parses, source included"):
    val json =
      """{"agents":{"generator":{"baseUrl":"http://localhost:8933/v1",
        |  "model":"/home/s/models/Qwen3.8-27B-GGUF/Qwen3.8-27B-UD-Q6_K_XL.gguf",
        |  "apiKeyEnv":"PAG_GENERATOR_KEY","temperature":0.7,"maxTokens":8000,"timeoutSeconds":600,"retries":1,
        |  "source":{"url":"https://huggingface.co/unsloth/Qwen3.8-27B-GGUF","file":"Qwen3.8-27B-UD-Q6_K_XL.gguf",
        |            "revision":null,"sha256":null}}}}""".stripMargin
    val g = CampaignConfig.parse(json).fold(e => fail(e), _.agents.generator)
    assertEquals((g.temperature, g.maxTokens, g.timeoutSeconds, g.retries, g.apiKeyEnv),
      (0.7, Some(8000), 600, 1, Some("PAG_GENERATOR_KEY")))
    assertEquals(g.source, ModelSource(Some("https://huggingface.co/unsloth/Qwen3.8-27B-GGUF"),
      Some("Qwen3.8-27B-UD-Q6_K_XL.gguf"), None, None))

  test("the sampling settings, the thinking switch and the budget parse"):
    val g = CampaignConfig.parse(
      """{"agents":{"generator":{"baseUrl":"http://x/v1","model":"m","temperature":0.6,"topP":0.95,"topK":20,
        |  "minP":0.0,"presencePenalty":0.0,"thinking":true,"maxTokens":32768}}}""".stripMargin)
      .fold(e => fail(e), _.agents.generator)
    assertEquals((g.topP, g.topK, g.minP, g.presencePenalty, g.thinking, g.maxTokens),
      (Some(0.95), Some(20), Some(0.0), Some(0.0), Some(true), Some(32768)))

  test("only baseUrl and model are required; everything else has a default"):
    val g = CampaignConfig.parse("""{"agents":{"generator":{"baseUrl":"http://x/v1","model":"m"}}}""")
      .fold(e => fail(e), _.agents.generator)
    assertEquals(g, AgentConfig("http://x/v1", "m"))
    assertEquals((g.apiKeyEnv, g.maxTokens, g.source), (None, None, ModelSource()))

  test("a missing required field is an error naming it"):
    val r = CampaignConfig.parse("""{"agents":{"generator":{"baseUrl":"http://x/v1"}}}""")
    assert(r.left.exists(_.contains("model")), r)

  test("the example config is refused until its placeholders are filled in"):
    val repo = Repo.root().fold(e => fail(e), identity)
    val r = CampaignConfig.read(repo.resolve("config/e1-rung0-qwen3.5.example.json"))
    assert(r.left.exists(e => e.contains("placeholders") && e.contains("<sha256sum of the GGUF>")), r)

  test("once filled in, the example parses to the chosen settings"):
    val repo = Repo.root().fold(e => fail(e), identity)
    val filled = java.nio.file.Files.readString(repo.resolve("config/e1-rung0-qwen3.5.example.json"))
      .replaceAll("\"<[^\"]*>\"", "\"filled\"")
    val g = CampaignConfig.parse(filled).fold(e => fail(e), _.agents.generator)
    assertEquals((g.temperature, g.topP, g.topK, g.minP, g.presencePenalty, g.thinking, g.maxTokens, g.timeoutSeconds),
      (0.6, Some(0.95), Some(20), Some(0.0), Some(0.0), Some(true), Some(32768), 2700))

  test("a missing file is an error naming it"):
    val r = CampaignConfig.read(java.nio.file.Path.of("/no/such/campaign.json"))
    assertEquals(r, Left("no config file at /no/such/campaign.json"))
