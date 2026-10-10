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

  /** proved and unsound follow from the cells: R proves, ✗ is unsound. */
  def evaluated(id: String, cells: List[String], ms: Long = 600000): AttemptRecord =
    RecordFixtures.evaluated(record, cells, cells.indices.map(i => s"P$i").toList, (_, c) => c == "✗")
      .copy(attempt = id, elapsedMs = ms)

  def status(attempt: Option[String], stage: String, stageFrom: Long, attemptFrom: Long = 0): Status =
    Status("c", 5, attempt, stage, at(stageFrom).toString, attempt.map(_ => at(attemptFrom).toString), 1800,
      at(stageFrom).toString, running = true)

  def screen(st: Option[Status], attempts: List[AttemptRecord], slots: Either[String, List[Slot]],
      history: List[Reading], now: Instant): String =
    StatusView.render(now, "c", st, attempts, slots, history).mkString("\n")

  test("progress: samples finished, the current stage, time per attempt, and an estimate"):
    val s = screen(Some(status(Some("attempt-003"), "asking the model", 0)),
      List(evaluated("attempt-001", List("R")), evaluated("attempt-002", List("A"))), Right(Nil), Nil, at(120))
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

  test("a new generation starting resets the rate: never negative, measured from the restart"):
    // attempt 002 ended at 32,768 tokens; attempt 003 started, and 20 s later is at 445
    val history = List(Reading(at(0), 30000), Reading(at(30), 32768), Reading(at(40), 100), Reading(at(60), 445))
    val restarted = Right(List(Slot(0, busy = true, 445, 2673, 65536)))
    val s = screen(Some(status(Some("attempt-003"), "asking the model", 40)), Nil, restarted, history, at(60))
    val rateLine = s.linesIterator.find(_.contains("tokens/s")).getOrElse(fail(s"no rate shown:\n$s"))
    assert(!rateLine.contains(" -"), rateLine)
    assert(s.contains("445 tokens generated,  17 tokens/s over the last minute"), s) // (445 - 100) / 20 s

  test("no stall is reported across a restart: the new generation is moving"):
    val history = List(Reading(at(0), 32768), Reading(at(70), 32768), Reading(at(80), 50), Reading(at(140), 900))
    val s = screen(Some(status(Some("attempt-002"), "asking the model", 75)), Nil,
      Right(List(Slot(0, busy = true, 900, 2673, 65536))), history, at(140))
    assert(!s.contains("stalled"), s)

  test("warnings: a stalled generation, one near its timeout, another busy slot"):
    val stalled = List(Reading(at(0), 47086), Reading(at(90), 47086))
    val twoBusy = Slots.parse(slotsJson).map(ss => ss.map(sl => if sl.id == 0 then sl.copy(busy = true) else sl))
    val s = screen(Some(status(Some("attempt-001"), "asking the model", 0)), Nil, twoBusy, stalled, at(1500))
    assert(s.contains("generation stalled"), s)
    assert(s.contains("generation near the client's timeout (5m00s left)"), s)
    assert(s.contains("1 other slot(s) busy"), s)

  test("finished attempts: a mini Table 1, failures counted, and a run of the same failure flagged"):
    val noFiles = (1 to 3).toList.map(i => record.copy(attempt = f"attempt-00${i + 1}%d"))
    val s = screen(None, evaluated("attempt-001", List("R", "A")) :: noFiles, Right(Nil), Nil, at(0))
    assert(s.contains("attempt-001  evaluated"), s)
    assert(s.contains("R A"), s)
    assert(s.contains("no files in the reply ×3"), s)
    assert(s.contains("the last 3 attempts all failed the same way (no files in the reply)"), s)

  test("a reply cut off at the token budget is 'ran out of tokens', not a missing file or a compile error"):
    val cut = RecordFixtures.withExchange(record.copy(attempt = "attempt-001")) { (reply, files, build) =>
      Exchange.Replied(reply.copy(finishReason = Some("length")), files, build)
    }
    assertEquals(StatusView.outcome(cut), "ran out of tokens")
    assert(screen(None, List(cut), Right(Nil), Nil, at(0)).contains("ran out of tokens ×1"))

  test("an unsound attempt and a killed pag are flagged"):
    val s = screen(None, List(evaluated("attempt-001", List("✗", "H"))), Right(Nil), Nil, at(0))
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
        val (dir, _) = Campaign.generate(Start.Fresh("c", 2), agent, ChatClient(agent), prompt, tools, results)
          .fold(e => fail(e), identity)
        val st = Status.read(dir).getOrElse(fail("no status.json"))
        assertEquals(st.campaign, dir.getFileName.toString)
        assert(st.startedAt.flatMap(t => scala.util.Try(Instant.parse(t)).toOption).isDefined, st)
        assertEquals((st.running, st.stage, st.samplesRequested, st.attempt), (false, "finished", 2, None))
      finally Using.resource(Files.walk(results))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
    }

  // Which run `campaign status` shows when none is named.

  /** A results directory holding these runs' status files, deleted afterwards. */
  def withRuns[A](runs: (String, Status)*)(body: Path => A): A =
    val results = Files.createTempDirectory("results")
    try
      for (name, st) <- runs do Status.write(results.resolve(name), st)
      body(results)
    finally Using.resource(Files.walk(results))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  def run(began: Long, running: Boolean, startedAt: Boolean = true): Status =
    status(None, if running then "asking the model" else "finished", began)
      .copy(running = running, startedAt = Option.when(startedAt)(at(began).toString))

  test("the run whose generate began last is shown, though an older crashed run still says running"):
    withRuns("e1-a-crashed" -> run(0, running = true), "e1-b-done" -> run(100, running = false)) { results =>
      val c = Status.latest(results).getOrElse(fail("none chosen"))
      assertEquals((c.dir.getFileName.toString, c.othersRunning), ("e1-b-done", List("e1-a-crashed")))
    }

  test("an older directory resumed just now is the one shown"):
    withRuns("e1-x-20261001T000000Z" -> run(500, running = true), "e1-x-20261005T000000Z" -> run(100, running = false)) {
      results => assertEquals(Status.latest(results).map(_.dir.getFileName.toString), Some("e1-x-20261001T000000Z"))
    }

  test("status files from before startedAt fall back to the last moment they record"):
    withRuns("old" -> run(300, running = false, startedAt = false), "new" -> run(200, running = false)) { results =>
      assertEquals(Status.latest(results).map(_.dir.getFileName.toString), Some("old"))
    }

  test("an unreadable status file is skipped, and with no runs at all nothing is chosen"):
    withRuns("good" -> run(0, running = false)) { results =>
      Files.createDirectories(results.resolve("bad"))
      Files.writeString(results.resolve("bad/status.json"), "{ not json")
      assertEquals(Status.latest(results).map(_.dir.getFileName.toString), Some("good"))
    }
    withRuns() { results => assertEquals(Status.latest(results), None) }
    assertEquals(Status.latest(Paths.get("/no/such/results")), None)

  test("the committed E1 runs: the 27B, finished last, is shown, and none still says running"):
    val committed = List("e1-rung0-qwen3.5-0.8B", "e1-rung0-qwen3.5-9B", "e1-rung0-qwen3.5-27B")
    withRuns(committed.map(n => n -> Status.read(repo.resolve("results").resolve(n)).getOrElse(fail(n)))*) { results =>
      val c = Status.latest(results).getOrElse(fail("none chosen"))
      assertEquals((c.dir.getFileName.toString, c.othersRunning), ("e1-rung0-qwen3.5-27B", Nil))
    }

  test("the screen says how the run was chosen, and lists the others still marked running"):
    val s = StatusView.render(at(10), "e1-b", Some(run(0, running = false)), Nil, Right(Nil), Nil,
      "most recent run", List("e1-a")).mkString("\n")
    assert(s.contains("campaign  e1-b  (most recent run)"), s)
    assert(s.contains("also      marked running: e1-a"), s)

  test("a running run quiet for longer than the client's timeout plus the margin may have crashed; not a moment before"):
    val st = run(0, running = true) // updated at 0; the client's timeout is 1800 s
    def warned(now: Long): Boolean =
      StatusView.render(at(now), "c", Some(st), Nil, Right(Nil), Nil).exists(_.contains("the run may have crashed"))
    val limit = 1800 + StatusView.CrashMarginSeconds
    assertEquals((warned(limit), warned(limit + 1)), (false, true))
    assert(!StatusView.render(at(99999), "c", Some(run(0, running = false)), Nil, Right(Nil), Nil)
      .exists(_.contains("may have crashed")), "a finished run is never flagged")
