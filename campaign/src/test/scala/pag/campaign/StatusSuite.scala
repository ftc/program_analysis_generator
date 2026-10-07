package pag.campaign

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Using

import pag.campaign.FakeServer.{completion, withServer}
import pag.cli.DomainJars.apiPath

/** `campaign status` (implementation_strategy.md Phase 10): reading llama.cpp's
  * slots, and every metric and warning on the screen, from fixed inputs.
  */
class StatusSuite extends munit.FunSuite:

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)
  val t0: Instant = Instant.parse("2026-10-07T17:00:00Z")
  def at(seconds: Long): Instant = t0.plusSeconds(seconds)

  /** /slots as llama.cpp reports it: busy slot 3 with 47086 tokens, others idle. */
  val slotsJson: String =
    """[{"id":0,"n_ctx":262144,"is_processing":false,"n_prompt_tokens":0,"next_token":[{"has_next_token":false,"n_remain":-1,"n_decoded":0}]},
      | {"id":3,"n_ctx":262144,"is_processing":true,"n_prompt_tokens":7524,"next_token":[{"has_next_token":true,"n_remain":-1,"n_decoded":47086}]}]""".stripMargin

  test("llama.cpp's /slots is read: which slot is busy, tokens so far, prompt and context size"):
    assertEquals(Slots.parse(slotsJson), Right(List(
      Slot(0, busy = false, 0, 0, 262144), Slot(3, busy = true, 47086, 7524, 262144))))

  /** One attempt record, made by a real (fast) run, to vary per test. */
  lazy val record: AttemptRecord =
    withServer(200 -> completion("no files")) { s =>
      val dir = Files.createTempDirectory("results")
      try
        val agent = AgentConfig(s.baseUrl, "m", retries = 0)
        val tools = Tools(List("true"), repo.resolve("domains/build-template"), apiPath,
          Paths.get(classOf[pag.probe.Rand].getProtectionDomain.getCodeSource.getLocation.toURI), repo.resolve("corpora/smoke"))
        val prompt = Prompt.assemble(repo, "generator-v1").fold(e => fail(e), identity)
        Attempt.run("c", 1, agent, ChatClient(agent), prompt, tools, dir.resolve("attempt-001"))
      finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
    }

  def evaluated(id: String, cells: List[String], proved: Int, unsound: Boolean = false, ms: Long = 600000): AttemptRecord =
    record.copy(attempt = id, elapsedMs = ms,
      summary = Summary(4, builds = true, 10, 0, loads = true, cells, proved, unsound))

  def status(attempt: Option[String], stage: String, stageFrom: Long, attemptFrom: Long = 0): Status =
    Status("c", 5, attempt, stage, at(stageFrom).toString, attempt.map(_ => at(attemptFrom).toString), 1800,
      at(stageFrom).toString, running = true)

  def screen(st: Option[Status], attempts: List[AttemptRecord], slots: Either[String, List[Slot]],
      history: List[Reading], now: Instant): String =
    StatusView.render(now, "c", st, attempts, slots, history).mkString("\n")

  test("progress: samples finished, the current stage, time per attempt, and an estimate"):
    val s = screen(Some(status(Some("attempt-003"), "asking the model", 0)),
      List(evaluated("attempt-001", List("R"), 1), evaluated("attempt-002", List("A"), 0)), Right(Nil), Nil, at(120))
    assert(s.contains("2 of 5 finished"), s)
    assert(s.contains("attempt-003: asking the model, for 2m00s"), s)
    assert(s.contains("10m00s per attempt"), s)
    assert(s.contains("about 28m00s to go"), s) // 3 left × 10 min, less the 2 min the current one has run

  test("the live generation: tokens, rate over the last minute, prompt, time to timeout, context left"):
    val history = List(Reading(at(0), 40000), Reading(at(60), 41680))
    val s = screen(Some(status(Some("attempt-001"), "asking the model", 0)), Nil,
      Slots.parse(slotsJson), history, at(60))
    assert(s.contains("slot 3: 47086 tokens generated,  28 tokens/s over the last minute,  prompt 7524 tokens"), s)
    assert(s.contains("29m00s before the client's timeout"), s)
    assert(s.contains(s"${262144 - 7524 - 47086} tokens of context left"), s)
    assert(s.contains("warnings  none"), s)

  test("warnings: a stalled generation, one near its timeout, another busy slot"):
    val stalled = List(Reading(at(0), 47086), Reading(at(90), 47086))
    val twoBusy = Slots.parse(slotsJson).map(ss => ss.map(sl => if sl.id == 0 then sl.copy(busy = true) else sl))
    val s = screen(Some(status(Some("attempt-001"), "asking the model", 0)), Nil, twoBusy, stalled, at(1500))
    assert(s.contains("generation stalled"), s)
    assert(s.contains("generation near the client's timeout (5m00s left)"), s)
    assert(s.contains("1 other slot(s) busy"), s)

  test("finished attempts: a mini Table 1, failures counted, and a run of the same failure flagged"):
    val noFiles = (1 to 3).toList.map(i => record.copy(attempt = f"attempt-00${i + 1}%d"))
    val s = screen(None, evaluated("attempt-001", List("R", "A"), 1) :: noFiles, Right(Nil), Nil, at(0))
    assert(s.contains("attempt-001  evaluated"), s)
    assert(s.contains("R A"), s)
    assert(s.contains("no files in the reply ×3"), s)
    assert(s.contains("the last 3 attempts all failed the same way (no files in the reply)"), s)

  test("an unsound attempt and a killed pag are flagged"):
    val s = screen(None, List(evaluated("attempt-001", List("✗", "H"), 1, unsound = true)), Right(Nil), Nil, at(0))
    assert(s.contains("unsound: attempt-001 refuted a reachable target"), s)
    assert(s.contains("a pag check was killed (H) in attempt-001"), s)

  test("an unreachable server is shown, not fatal"):
    val s = screen(None, Nil, Left("/slots: no response"), Nil, at(0))
    assert(s.contains("model     /slots: no response"), s)

  test("generate leaves status.json saying it finished, with the samples it was asked for"):
    withServer(200 -> completion("no files")) { s =>
      val results = Files.createTempDirectory("results")
      try
        val agent = AgentConfig(s.baseUrl, "m", retries = 0)
        val tools = Tools(List("true"), repo.resolve("domains/build-template"), apiPath,
          Paths.get(classOf[pag.probe.Rand].getProtectionDomain.getCodeSource.getLocation.toURI), repo.resolve("corpora/smoke"))
        val prompt = Prompt.assemble(repo, "generator-v1").fold(e => fail(e), identity)
        Campaign.generate("c", 2, agent, ChatClient(agent), prompt, tools, results).fold(e => fail(e), identity)
        val st = Status.read(results.resolve("c")).getOrElse(fail("no status.json"))
        assertEquals((st.running, st.stage, st.samplesRequested, st.attempt), (false, "finished", 2, None))
      finally Using.resource(Files.walk(results))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
    }
