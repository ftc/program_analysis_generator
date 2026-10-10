package pag.campaign

import pag.campaign.FakeServer.{completion, withServer}

/** The chat client (implementation_strategy.md §10, §12) against a fake
  * OpenAI-compatible server: what it sends, what it reads back, and which
  * failures it retries.
  */
class ChatClientSuite extends munit.FunSuite:

  def status(f: ChatFailure): Option[Int] = f match
    case b: ChatFailure.BadResponse => Some(b.status)
    case _: ChatFailure.NoResponse  => None

  val hello: List[ChatMessage] = List(ChatMessage("system", "be brief"), ChatMessage("user", "hi"))

  /** A client that records its backoff sleeps instead of sleeping. */
  def client(baseUrl: String, retries: Int = 3, apiKeyEnv: Option[String] = None,
      env: Map[String, String] = Map.empty): (ChatClient, java.util.concurrent.ConcurrentLinkedQueue[Long]) =
    val slept = java.util.concurrent.ConcurrentLinkedQueue[Long]() // observes the client's sleeps
    val agent = AgentConfig(baseUrl, "m.gguf", apiKeyEnv = apiKeyEnv, temperature = 0.3, retries = retries)
    (ChatClient(agent, env.get, ms => { slept.add(ms); () }), slept)

  test("sends the model, the messages and the temperature to /v1/chat/completions, and no max_tokens when unset"):
    withServer(200 -> completion("ok")) { s =>
      client(s.baseUrl)._1.chat(hello)
      val r = s.received.head
      assertEquals((r.method, r.path), ("POST", "/v1/chat/completions"))
      assertEquals(r.body,
        """{"model":"m.gguf","messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi"}],"temperature":0.3}""")
    }

  test("the sampling settings and the thinking switch are sent under the API's names when set"):
    withServer(200 -> completion("ok")) { s =>
      val agent = AgentConfig(s.baseUrl, "m", temperature = 0.6, topP = Some(0.95), topK = Some(20), minP = Some(0.0),
        presencePenalty = Some(0.0), thinking = Some(true), maxTokens = Some(32768))
      ChatClient(agent).chat(List(ChatMessage("user", "hi")))
      assertEquals(s.received.head.body,
        """{"model":"m","messages":[{"role":"user","content":"hi"}],"temperature":0.6,"max_tokens":32768,""" +
          """"top_p":0.95,"top_k":20,"min_p":0.0,"presence_penalty":0.0,"chat_template_kwargs":{"enable_thinking":true}}""")
    }

  test("max_tokens is sent under the API's name when set"):
    withServer(200 -> completion("ok")) { s =>
      ChatClient(AgentConfig(s.baseUrl, "m", maxTokens = Some(500))).chat(hello)
      assert(s.received.head.body.contains("\"max_tokens\":500"), s.received.head.body)
    }

  test("reads the content, the reasoning, the usage and the model; keeps the raw body; ignores the rest"):
    withServer(200 -> completion("the answer", reasoning = Some("thinking"))) { s =>
      val reply = client(s.baseUrl)._1.chat(hello).fold(f => fail(f.toString), identity)
      assertEquals((reply.content, reply.reasoning, reply.finishReason), ("the answer", Some("thinking"), Some("stop")))
      assertEquals((reply.promptTokens, reply.completionTokens, reply.model), (Some(120L), Some(45L), Some("m.gguf")))
      assertEquals((reply.rawResponse, reply.tries), (completion("the answer", Some("thinking")), 1))
    }

  test("no Authorization header without a key; Bearer with the key the named variable holds"):
    withServer(200 -> completion("ok")) { s =>
      client(s.baseUrl)._1.chat(hello)
      client(s.baseUrl, apiKeyEnv = Some("PAG_KEY"))._1.chat(hello) // named, but unset
      client(s.baseUrl, apiKeyEnv = Some("PAG_KEY"), env = Map("PAG_KEY" -> "sekrit"))._1.chat(hello)
      assertEquals(s.received.map(_.authorization), List(None, None, Some("Bearer sekrit")))
    }

  test("a 5xx is retried with backoff, and a later success is returned"):
    withServer(500 -> "busy", 503 -> "busy", 200 -> completion("ok")) { s =>
      val (c, slept) = client(s.baseUrl)
      val reply = c.chat(hello).fold(f => fail(f.toString), identity)
      assertEquals((reply.content, reply.tries), ("ok", 3))
      assertEquals(slept.toArray.toList, List[Any](1000L, 2000L))
    }

  test("429 is retried too"):
    withServer(429 -> "slow down", 200 -> completion("ok")) { s =>
      assertEquals(client(s.baseUrl)._1.chat(hello).map(_.tries), Right(2))
    }

  test("retries are bounded: the last failure is returned with its status and body"):
    withServer(503 -> "down") { s =>
      val failure = client(s.baseUrl, retries = 2)._1.chat(hello).fold(identity, r => fail(r.toString))
      assertEquals(failure, ChatFailure.BadResponse("HTTP 503", 503, "down", 3))
    }

  test("a 4xx other than 429 is not retried"):
    withServer(400 -> """{"error":"bad model"}""") { s =>
      val (c, slept) = client(s.baseUrl)
      assertEquals(c.chat(hello).left.map(f => (status(f), f.tries)), Left((Some(400), 1)))
      assert(slept.isEmpty)
    }

  test("a 200 without a message is a failure, not retried"):
    withServer(200 -> """{"choices":[]}""") { s =>
      val failure = client(s.baseUrl)._1.chat(hello).fold(identity, r => fail(r.toString))
      assertEquals((status(failure), failure.tries), (Some(200), 1))
      assert(failure.message.contains("choices[0].message.content"), failure.message)
    }

  test("no server at all: retried, then a failure saying there was no response"):
    val port = { val s = java.net.ServerSocket(0); try s.getLocalPort finally s.close() } // free, and now closed
    val (c, slept) = client(s"http://127.0.0.1:$port/v1", retries = 1)
    val failure = c.chat(hello).fold(identity, r => fail(r.toString))
    assert(failure.message.startsWith("no response"), failure.message)
    assertEquals((failure.tries, slept.size), (2, 1))

  test("a timeout is not retried: the model is slow, not gone, and a retry would start over"):
    val server = FakeServer(List(200 -> completion("too late")), delayMs = 3000)
    try
      val slept = java.util.concurrent.ConcurrentLinkedQueue[Long]()
      val agent = AgentConfig(server.baseUrl, "m", timeoutSeconds = 1, retries = 3)
      val failure = ChatClient(agent, sleep = ms => { slept.add(ms); () }).chat(hello).fold(identity, r => fail(r.toString))
      assertEquals((failure.message, failure.tries), ("no reply within 1 s (not retried)", 1))
      assert(slept.isEmpty, "no backoff, no second try")
      assertEquals(server.received.size, 1)
    finally server.stop()

  test("models() returns the server's own report, raw"):
    withServer(200 -> """{"data":[{"id":"m.gguf"}]}""") { s =>
      assertEquals(client(s.baseUrl)._1.models(), Right("""{"data":[{"id":"m.gguf"}]}"""))
      assertEquals(s.received.head.path, "/v1/models")
    }
