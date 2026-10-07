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

  test("only baseUrl and model are required; everything else has a default"):
    val g = CampaignConfig.parse("""{"agents":{"generator":{"baseUrl":"http://x/v1","model":"m"}}}""")
      .fold(e => fail(e), _.agents.generator)
    assertEquals(g, AgentConfig("http://x/v1", "m"))
    assertEquals((g.apiKeyEnv, g.maxTokens, g.source), (None, None, ModelSource()))

  test("a missing required field is an error naming it"):
    val r = CampaignConfig.parse("""{"agents":{"generator":{"baseUrl":"http://x/v1"}}}""")
    assert(r.left.exists(_.contains("model")), r)

  test("a missing file is an error naming it"):
    val r = CampaignConfig.read(java.nio.file.Path.of("/no/such/campaign.json"))
    assertEquals(r, Left("no config file at /no/such/campaign.json"))
